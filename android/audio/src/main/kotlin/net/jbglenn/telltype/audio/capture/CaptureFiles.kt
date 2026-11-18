package net.jbglenn.telltype.audio.capture

import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.audio.store.RecordingFiles
import net.jbglenn.telltype.audio.store.StoredRecording
import net.jbglenn.telltype.audio.store.isNoSpace
import net.jbglenn.telltype.audio.wav.RecordingSink
import net.jbglenn.telltype.audio.wav.RecordingSinkFactory
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull

/**
 * The recorder's file work, on the I/O dispatcher: opening a recording's file for writing, finalising it, and
 * deleting it. Every failure is logged and returned typed; deletion never fails.
 */
internal class CaptureFiles @Inject constructor(
    private val files: RecordingFiles,
    private val sinks: RecordingSinkFactory,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    /**
     * Finds [recording]'s file and opens it for appending [format] audio. Not cancellable, so an opened file always
     * reaches the caller, whose cleanup closes it.
     */
    suspend fun open(recording: RecordingId, format: AudioFormat): Outcome<OpenRecording, CaptureError> =
        withContext(dispatchers.io + NonCancellable) {
            val stored = files.find(recording)
            if (stored == null) {
                Outcome.Err(StorageFailed)
            } else {
                try {
                    Outcome.Ok(OpenRecording(stored, sinks.open(stored.file, format)))
                } catch (error: IOException) {
                    logger.warn(TAG, error) { "could not open recording ${recording.value}" }
                    Outcome.Err(if (error.isNoSpace()) StorageFull else StorageFailed)
                }
            }
        }

    /** Marks [recording] as being written, so the store neither evicts nor finalises it meanwhile. */
    fun markWriting(recording: RecordingId, active: Boolean) = files.markWriting(recording, active)

    /** Writes the final header; null (and the file kept for the orphan scan) when that fails. */
    suspend fun finalise(open: OpenRecording): Long? = withContext(dispatchers.io) {
        try {
            open.sink.finalise()
        } catch (error: IOException) {
            logger.warn(TAG, error) { "could not finalise recording ${open.stored.recording.value}; kept" }
            null
        }
    }

    /** Closes and deletes an open recording. Never fails. */
    suspend fun discard(open: OpenRecording) = withContext(dispatchers.io) {
        open.sink.close()?.let { failure -> logger.warn(TAG, failure) { "closing a discarded recording failed" } }
        deleteFile(open.stored)
    }

    /** Deletes [recording]'s file if it exists. Never fails. */
    suspend fun delete(recording: RecordingId) = withContext(dispatchers.io) {
        files.find(recording)?.let(::deleteFile)
    }

    private fun deleteFile(stored: StoredRecording) {
        val file = stored.file
        if (!file.delete() && file.exists()) logger.warn(TAG, null) { "could not delete ${file.name}" }
    }

    private companion object {
        val TAG = LogTag("CaptureFiles")
    }
}

/**
 * A recording file open for writing.
 *
 * @property stored the file and its ids.
 * @property sink where the samples go.
 */
internal data class OpenRecording(val stored: StoredRecording, val sink: RecordingSink)
