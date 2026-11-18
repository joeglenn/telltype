package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.flatMap
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.common.mapError
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.SessionEvent

/**
 * The `StartRecording` sequence: microphone service, then a recording file from the store, then the recorder
 * (docs/PLAN.md section 7.3). Replies `RecordingStarted` or `RecordingFailed`. Cancelled half-way (Abort, close),
 * it stops the recorder and deletes the file before it ends.
 */
internal class RecordingOpener(
    private val ctx: CoordinatorContext,
    private val files: CaptureFiles,
    private val microphone: MicrophoneHold,
) {
    /** Opens the recording for [record]; on success the record knows the recording and the start times. */
    suspend fun open(record: SessionRecord): SessionEvent {
        var file: RecordingId? = null
        var opened = false
        try {
            val outcome = microphone.acquire()
                .flatMap { createFile(record.id, record.field.incognito) }
                .flatMap { recording ->
                    file = recording
                    startRecorder(recording)
                }
            val event = when (outcome) {
                is Outcome.Ok -> started(record, outcome.value)
                is Outcome.Err -> SessionEvent.RecordingFailed(outcome.error).also { files.discard(file) }
            }
            opened = true
            return event
        } finally {
            if (!opened) {
                withContext(NonCancellable) {
                    files.discard(file)
                    microphone.releaseIfIdle()
                }
            }
        }
    }

    /**
     * The text around the cursor for [record] (section 4.3): never for incognito fields; only the selection when
     * reading around the cursor is switched off (Command Mode still needs it). Empty on a timeout.
     */
    suspend fun readContext(record: SessionRecord): FieldContext {
        val settings = ctx.settings.context
        val wanted = !record.field.incognito && (record.mode == BubbleMode.COMMAND || settings.readAroundCursor)
        val before = if (settings.readAroundCursor) settings.charsBefore else 0
        val after = if (settings.readAroundCursor) settings.charsAfter else 0
        return if (wanted) {
            ctx.calls.call("context read", ctx.config.contextRead, FieldContext.Empty) {
                ctx.ports.contextReader.read(record.field, before, after)
            }
        } else {
            FieldContext.Empty
        }
    }

    private suspend fun createFile(session: SessionId, incognito: Boolean): Outcome<RecordingId, CaptureError> {
        val failure: Outcome<RecordingId, StorageError> = Outcome.Err(StorageFailed)
        return ctx.calls.call("recording create", ctx.config.storage, failure) {
            ctx.ports.recordings.create(session, incognito)
        }
            .mapError { it.asCaptureError() }
    }

    private suspend fun startRecorder(recording: RecordingId): Outcome<RecordingId, CaptureError> {
        val failure: Outcome<Unit, CaptureError> = Outcome.Err(RecorderFailed)
        return ctx.calls.call("audio start", ctx.config.recorderStart, failure) { ctx.ports.audio.start(recording) }
            .map { recording }
    }

    private fun started(record: SessionRecord, recording: RecordingId): SessionEvent {
        val now = ctx.clock.now()
        record.recording = recording
        record.startedAt = now
        record.openedAt = ctx.clock.monotonic()
        return SessionEvent.RecordingStarted(record.id, recording, now)
    }
}
