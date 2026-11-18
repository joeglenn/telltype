package net.jbglenn.telltype.audio.capture

import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import net.jbglenn.telltype.audio.analysis.LevelMeter
import net.jbglenn.telltype.audio.analysis.SpeechDetector
import net.jbglenn.telltype.audio.analysis.SpeechVerdict
import net.jbglenn.telltype.audio.analysis.ZeroInputWatch
import net.jbglenn.telltype.audio.platform.Registration
import net.jbglenn.telltype.audio.store.isNoSpace
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull

/** Why capture ended. The first cause wins. */
internal sealed interface EndCause {
    /** `stop` or `cancel` asked for it. */
    data object Requested : EndCause

    /** Something outside the app ended it, or the input or the file failed. */
    data class Interrupted(val interruption: Interruption) : EndCause
}

/** What `stop` makes of a finished recording. */
internal sealed interface StopDecision {
    /** Hand the recording over as a clip. */
    data class Deliver(val speech: SpeechPresence) : StopDecision

    /** Report [error]; delete the file unless [keepFile]. */
    data class Fail(val error: CaptureError, val keepFile: Boolean) : StopDecision
}

/**
 * One recording: the capture loop and what it measured. The loop runs on the audio dispatcher and is the only
 * writer of the sink and the analysers; other threads only call [end] and read the rest after the loop
 * has finished (`Job.join` orders the two).
 *
 * The loop reads one [BUFFER_SAMPLES] buffer (20 ms) at a time into a single array that is reused for the whole
 * recording, writes it to the file at once and updates the meter and the checks in place, so memory stays the same
 * whether the recording lasts one second or six minutes.
 *
 * @property open the recording's file, open for writing.
 * @param onInterrupted called (from any thread) with the first interruption once the recording is running.
 */
internal class CaptureRun(
    val open: OpenRecording,
    private val format: AudioFormat,
    private val onInterrupted: (Interruption) -> Unit,
) {
    /** The recording's id. */
    val recording: RecordingId get() = open.stored.recording

    /** The waveform level of this recording. */
    val meter: LevelMeter = LevelMeter()

    /** Completes once audio flows (the first buffer is on disk), or with the reason it never will. */
    val started: CompletableDeferred<Outcome<Unit, CaptureError>> = CompletableDeferred()

    /** The capture loop, once launched. */
    @Volatile
    var job: Job? = null

    private val detector = SpeechDetector()
    private val zeros = ZeroInputWatch()
    private val buffer = ShortArray(BUFFER_SAMPLES)
    private val endCause = AtomicReference<EndCause?>(null)
    private val registrations = mutableListOf<Registration>()

    @Volatile
    private var running = false

    @Volatile
    private var writeFailure: CaptureError? = null

    /** Keeps [registration] until capture ends; released by [releaseRegistrations]. */
    fun hold(registration: Registration?) {
        if (registration == null) return
        synchronized(registrations) { registrations += registration }
    }

    /** Releases everything [hold] kept. Idempotent. */
    fun releaseRegistrations() {
        val held = synchronized(registrations) { registrations.toList().also { registrations.clear() } }
        held.forEach(Registration::release)
    }

    /** Ends capture for [cause] (the first cause wins); reports it as an interruption once recording runs. */
    fun end(cause: EndCause) {
        if (!endCause.compareAndSet(null, cause)) return
        if (cause is EndCause.Interrupted && running) onInterrupted(cause.interruption)
    }

    /** Opens the microphone and captures until [end], a failure or cancellation. Releases everything it opened. */
    suspend fun capture(sources: PcmSourceFactory) {
        try {
            when (val opened = sources.open(format) { end(EndCause.Interrupted(Interruption.MICROPHONE_LOST)) }) {
                is Outcome.Err -> started.complete(opened)

                is Outcome.Ok -> try {
                    readUntilEnded(opened.value)
                } finally {
                    opened.value.close()
                }
            }
        } finally {
            started.complete(Outcome.Err(RecorderFailed))
            releaseRegistrations()
        }
    }

    /** The verdict for this recording, once the loop has finished. */
    fun decide(): StopDecision {
        val verdict = detector.verdict()
        val failure = writeFailure
        return when {
            !zeros.sawSignal && (zeros.inUse || endCause.get() == MicrophoneLost) ->
                StopDecision.Fail(MicrophoneInUse, keepFile = false)

            failure != null && verdict != SpeechVerdict.CLEAR -> StopDecision.Fail(
                failure,
                keepFile = failure.keepsAudio,
            )

            verdict == SpeechVerdict.NONE -> StopDecision.Fail(NoSpeech, keepFile = false)

            verdict == SpeechVerdict.FAINT -> StopDecision.Deliver(SpeechPresence.FAINT)

            else -> StopDecision.Deliver(SpeechPresence.CLEAR)
        }
    }

    private suspend fun readUntilEnded(source: PcmSource) {
        var emptyReads = 0
        while (currentCoroutineContext().isActive && endCause.get() == null) {
            val count = source.read(buffer)
            when {
                count > 0 -> {
                    emptyReads = 0
                    if (!accept(count)) return
                }

                count == 0 && ++emptyReads <= MAX_EMPTY_READS -> Unit

                else -> {
                    fail(RecorderFailed, Interruption.MICROPHONE_LOST)
                    return
                }
            }
        }
    }

    /** Writes and measures one buffer; false when the file failed and capture must end. */
    private fun accept(count: Int): Boolean {
        try {
            open.sink.write(buffer, count)
        } catch (error: IOException) {
            val failure = if (error.isNoSpace()) StorageFull else StorageFailed
            writeFailure = failure
            fail(failure, Interruption.RECORDING_FAILED)
            return false
        }
        meter.update(buffer, count)
        detector.accept(buffer, count)
        zeros.accept(buffer, count)
        if (!running) {
            running = true
            started.complete(Outcome.Ok(Unit))
        }
        if (zeros.inUse || zeros.lost) end(MicrophoneLost)
        return true
    }

    /** Before audio flowed the start fails with [beforeStart]; afterwards capture ends with [afterStart]. */
    private fun fail(beforeStart: CaptureError, afterStart: Interruption) {
        if (running) end(EndCause.Interrupted(afterStart)) else started.complete(Outcome.Err(beforeStart))
    }

    companion object {
        /** Samples per read: 20 ms at 16 kHz. */
        const val BUFFER_SAMPLES: Int = 320

        /** Reads in a row that may return nothing before the input counts as failed. */
        const val MAX_EMPTY_READS: Int = 50

        private val MicrophoneLost = EndCause.Interrupted(Interruption.MICROPHONE_LOST)
    }
}
