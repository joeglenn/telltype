package net.jbglenn.telltype.domain.coordinator

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull

/** Recorder and file clean-up shared by the capture handlers; each call bounded and never throwing. */
internal class CaptureFiles(private val ctx: CoordinatorContext) {
    /** Stops the recorder without keeping its file and deletes [recording] (cancel, abort, failed start). */
    suspend fun discard(recording: RecordingId?) {
        if (recording != null) {
            cancelRecorder()
            delete(recording)
        }
    }

    /** `AudioCapture.cancel`, bounded. */
    suspend fun cancelRecorder() {
        ctx.calls.call("audio cancel", ctx.config.recorderStop, Unit) { ctx.ports.audio.cancel() }
    }

    /** Stops the recorder and keeps the file (close, or a stop whose caller was cancelled); the result is unused. */
    suspend fun stopKeepingFile() {
        ctx.calls.call("audio stop", ctx.config.recorderStop, Unit) {
            ctx.ports.audio.stop()
        }
    }

    /** Deletes [recording]; a failure is logged (the orphan scan finds the file at the next start). */
    suspend fun delete(recording: RecordingId) {
        val failure: Outcome<Unit, StorageError> = Outcome.Err(StorageFailed)
        val result = ctx.calls.call("recording delete", ctx.config.storage, failure) {
            ctx.ports.recordings.delete(recording)
        }
        if (result is Outcome.Err) {
            ctx.logger.warn(CoordinatorTag, null) { "recording delete failed: ${result.error.code}" }
        }
    }
}

/** A storage error while recording, as the capture error the reducer expects (both cases are capture errors). */
internal fun StorageError.asCaptureError(): CaptureError = when (this) {
    StorageFull -> StorageFull
    StorageFailed -> StorageFailed
}
