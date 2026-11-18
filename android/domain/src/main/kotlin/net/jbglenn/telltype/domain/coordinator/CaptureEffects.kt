package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * The recording effects: `StartRecording`, `AbortRecordingStart`, `StopRecording`, `CancelRecording` and
 * `DeleteRecording`. Every recorder operation goes through the capture lane, so a cancel can never overtake a start
 * or a stop. File deletions run in the coordinator's scope, not the session's: `CancelWork` must not cancel them.
 */
internal class CaptureEffects(private val ctx: CoordinatorContext) {
    private val files = CaptureFiles(ctx)
    private val microphone = MicrophoneHold(ctx)
    private val opener = RecordingOpener(ctx, files, microphone)

    /** Makes the session id, opens its job, starts the context read and `prepare` and opens the recording. */
    fun start(effect: SessionEffect.StartRecording) {
        val id = ctx.environment.ids.newSessionId()
        val record = SessionRecord(id, effect.field, effect.mode, ctx.styleFor(effect.field.app))
        val scope = ctx.sessions.open(record)
        record.context = scope.async { opener.readContext(record) }
        scope.launch { ctx.calls.call("speech prepare", ctx.config.prepare, Unit) { ctx.ports.speech.prepare() } }
        ctx.captureLane.launch(scope) { ctx.send(Inbound.StartReply(id, opener.open(record))) }
    }

    /**
     * Cancels the pending start. A start still running cleans up after itself; one that already succeeded (its
     * reply not yet processed, and now stale) has its recording cancelled here.
     */
    fun abort() {
        ctx.sessions.abortStart()?.recording?.let(::cancelRecording)
    }

    /** Stops the recorder; replies `AudioFinalised` or `AudioFailed`. Measures the recording stage. */
    fun stop(effect: SessionEffect.StopRecording) {
        val (scope, record) = ctx.sessions.find(effect.session) ?: return ctx.dropped(effect)
        val stopping = ctx.clock.monotonic()
        record.stoppedAt = stopping
        record.timings = record.timings.copy(recording = record.openedAt?.let { stopping - it })
        ctx.captureLane.launch(scope) { ctx.reply(finish(effect, record, stopping)) }
    }

    /** Stops the recorder without keeping the file and deletes [recording]. */
    fun cancelRecording(recording: RecordingId) {
        ctx.captureLane.launch(ctx.work) { files.discard(recording) }
    }

    /** Deletes a finished recording. */
    fun deleteRecording(recording: RecordingId) {
        ctx.captureLane.launch(ctx.work) { files.delete(recording) }
    }

    /** After each transition: the microphone service is stopped once nothing records or processes. */
    fun settle() {
        microphone.releaseIfIdle()
    }

    /** On close: a recording in progress is stopped and kept for recovery; the microphone service is stopped. */
    suspend fun teardown(state: SessionState) {
        if (state is SessionState.Listening) files.stopKeepingFile()
        microphone.releaseNow()
    }

    private suspend fun finish(
        effect: SessionEffect.StopRecording,
        record: SessionRecord,
        stopping: Duration,
    ): SessionEvent {
        var settled = false
        try {
            val outcome = ctx.calls.attempt("audio stop", ctx.config.recorderStop) { ctx.ports.audio.stop() }
                ?: abandon()
            settled = true
            record.timings = record.timings.copy(finalising = ctx.clock.monotonic() - stopping)
            return when (outcome) {
                is Outcome.Ok -> finalised(effect, record, outcome.value)
                is Outcome.Err -> SessionEvent.AudioFailed(effect.session, outcome.error)
            }
        } finally {
            if (!settled) withContext(NonCancellable) { files.stopKeepingFile() }
        }
    }

    private fun finalised(effect: SessionEffect.StopRecording, record: SessionRecord, clip: AudioClip): SessionEvent {
        record.audioDuration = clip.duration
        return SessionEvent.AudioFinalised(effect.session, clip)
    }

    /** A stop that timed out or threw leaves the recorder in an unknown state: cancel it and report the failure. */
    private suspend fun abandon(): Outcome<AudioClip, CaptureError> {
        files.cancelRecorder()
        return Outcome.Err(RecorderFailed)
    }
}
