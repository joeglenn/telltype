package net.jbglenn.telltype.audio.capture

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.audio.analysis.LevelMeter
import net.jbglenn.telltype.audio.store.toClip
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.error
import net.jbglenn.telltype.core.common.flatMap
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.AudioLevel
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.domain.port.AudioCapture
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * The microphone (docs/PLAN.md sections 7.3 and 8.1): one recording at a time, captured by a [CaptureRun] on the
 * audio dispatcher and written to its file as it arrives.
 *
 * - [start] checks the permission, opens the file, takes audio focus when "Pause other audio while recording" is on,
 *   watches for screen-off and calls, and returns once the first buffer is on disk, or fails within
 *   [START_TIMEOUT]. A failed or cancelled start deletes the recording's file and releases everything.
 * - Interruptions (screen off, call, focus lost, microphone lost, file failed) stop capture at once, keep the audio
 *   and are published on [interruptions]; the coordinator then calls [stop].
 * - [stop] finalises the file and returns the clip, or deletes it and fails with `NoSpeech` or `MicrophoneInUse`.
 * - [cancel] deletes the file.
 *
 * Works the same whether it is driven from the coordinator or from `:platform`'s microphone service: it holds no
 * Android component, only its own scope (a supervisor on the audio dispatcher) for the capture loop.
 */
@Singleton
internal class AudioRecorder @Inject constructor(
    private val files: CaptureFiles,
    private val devices: CaptureDevices,
    private val settings: SettingsRepository,
    dispatchers: DispatcherProvider,
    private val logger: Logger,
) : AudioCapture {
    private val format = AudioFormat.Dictation
    private val mutex = Mutex()
    private val scope = CoroutineScope(
        SupervisorJob() + dispatchers.audio + CoroutineExceptionHandler { _, failure ->
            logger.error(TAG, failure) { "capture loop failed; the recording keeps what was written" }
        },
    )
    private val activeMeter = MutableStateFlow<LevelMeter?>(null)
    private val interruptionFlow = MutableSharedFlow<Interruption>(extraBufferCapacity = INTERRUPTION_BUFFER)

    /** The running recording, between a successful [start] and [stop] or [cancel]. Guarded by [mutex]. */
    private var active: CaptureRun? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override val levels: Flow<AudioLevel> = activeMeter.flatMapLatest { meter -> meter?.ticks() ?: emptyFlow() }

    override val interruptions: Flow<Interruption> = interruptionFlow.asSharedFlow()

    override suspend fun start(recording: RecordingId): Outcome<Unit, CaptureError> = try {
        mutex.withLock { startLocked(recording) }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            mutex.withLock { if (active?.recording != recording) files.delete(recording) }
        }
        throw cancelled
    }

    override suspend fun stop(): Outcome<AudioClip, CaptureError> = mutex.withLock {
        val run = active ?: return Outcome.Err(RecorderFailed)
        active = null
        withContext(NonCancellable) { finish(run) }
    }

    override suspend fun cancel() {
        mutex.withLock {
            val run = active ?: return
            active = null
            withContext(NonCancellable) { discard(run) }
            logger.info(TAG) { "recording ${run.recording.value} cancelled" }
        }
    }

    /** [start] under the lock: any outcome but success leaves no file, no recorder, no focus and no watcher. */
    private suspend fun startLocked(recording: RecordingId): Outcome<Unit, CaptureError> {
        val running = active
        if (running != null) {
            logger.warn(TAG, null) { "start while recording ${running.recording.value}; refused" }
            if (running.recording != recording) files.delete(recording)
            return Outcome.Err(RecorderFailed)
        }
        var run: CaptureRun? = null
        var result: Outcome<Unit, CaptureError> = Outcome.Err(RecorderFailed)
        try {
            result = if (devices.permission.isGranted()) {
                files.open(recording, format).flatMap { open ->
                    val created = CaptureRun(open, format) { cause -> interruptionFlow.tryEmit(cause) }
                    run = created
                    withTimeoutOrNull(START_TIMEOUT) { begin(created) } ?: timedOut()
                }
            } else {
                Outcome.Err(MicrophonePermissionMissing)
            }
        } finally {
            val created = run
            if (result is Outcome.Ok && created != null) {
                active = created
                logger.info(TAG) { "recording ${recording.value} started" }
            } else {
                withContext(NonCancellable) { if (created != null) discard(created) else files.delete(recording) }
            }
        }
        return result
    }

    private fun timedOut(): Outcome<Unit, CaptureError> {
        logger.warn(TAG, null) { "microphone did not start within $START_TIMEOUT" }
        return Outcome.Err(RecorderFailed)
    }

    /** Takes focus, watches for interruptions, launches the loop and waits until audio flows. */
    private suspend fun begin(run: CaptureRun): Outcome<Unit, CaptureError> {
        files.markWriting(run.recording, active = true)
        if (settings.current().dictation.pauseOtherAudio) {
            run.hold(devices.focus.request { run.end(EndCause.Interrupted(Interruption.AUDIO_FOCUS_LOST)) })
        }
        run.hold(devices.monitor.watch { cause -> run.end(EndCause.Interrupted(cause)) })
        run.job = scope.launch {
            try {
                run.capture(devices.sources)
            } finally {
                activeMeter.compareAndSet(run.meter, null)
            }
        }
        val outcome = run.started.await()
        if (outcome is Outcome.Ok && run.job?.isActive == true) activeMeter.value = run.meter
        return outcome
    }

    /** Ends capture, finalises the file and turns it into a clip or a typed error. */
    private suspend fun finish(run: CaptureRun): Outcome<AudioClip, CaptureError> {
        endCapture(run)
        return when (val decision = run.decide()) {
            is StopDecision.Fail -> {
                if (decision.keepFile) files.finalise(run.open) else files.discard(run.open)
                logger.info(TAG) { "recording ${run.recording.value} ended: ${decision.error.code}" }
                Outcome.Err(decision.error)
            }

            is StopDecision.Deliver -> {
                val dataBytes = files.finalise(run.open)
                logger.info(TAG) { "recording ${run.recording.value} stopped: ${decision.speech}" }
                if (dataBytes == null) {
                    Outcome.Err(StorageFailed)
                } else {
                    Outcome.Ok(run.open.stored.toClip(dataBytes, format, decision.speech))
                }
            }
        }
    }

    /** Abandons capture at once and deletes the file. Never fails. */
    private suspend fun discard(run: CaptureRun) {
        run.job?.cancel()
        endCapture(run)
        files.discard(run.open)
    }

    /** Stops the loop (waiting at most [STOP_TIMEOUT]) and releases the microphone, focus and watchers. */
    private suspend fun endCapture(run: CaptureRun) {
        run.end(EndCause.Requested)
        run.job?.let { job ->
            if (withTimeoutOrNull(STOP_TIMEOUT) { job.join() } == null) {
                logger.warn(TAG, null) { "capture loop did not stop within $STOP_TIMEOUT" }
                job.cancel()
            }
        }
        run.releaseRegistrations()
        activeMeter.compareAndSet(run.meter, null)
        files.markWriting(run.recording, active = false)
    }

    companion object {
        /** How long [start] may take before it fails with `RecorderFailed` (the target is 200 ms, section 16). */
        val START_TIMEOUT: Duration = 2.seconds

        /** How long stopping waits for the capture loop to finish its last read. */
        val STOP_TIMEOUT: Duration = 1.seconds

        private const val INTERRUPTION_BUFFER = 8
        private val TAG = LogTag("AudioRecorder")
    }
}
