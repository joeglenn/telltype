package net.jbglenn.telltype.audio.store

import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.audio.analysis.SpeechDetector
import net.jbglenn.telltype.audio.wav.WavHeader
import net.jbglenn.telltype.audio.wav.WavRepair
import net.jbglenn.telltype.audio.wav.WavSamples
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.domain.port.RecordingStore

/**
 * Recording files in app-private storage ([RecordingFiles]). All file work runs on the I/O dispatcher; every
 * failure is a [StorageError], never an exception.
 *
 * Low space (docs/PLAN.md section 10, "Storage full"): when less than [LOW_SPACE_BYTES] are free, [create] deletes
 * the oldest retained recordings (kept for Retry or queued) until there is room again, and refuses with [StorageFull]
 * only when even then less than [MIN_FREE_BYTES] remain. The recording being written is never deleted.
 */
@Singleton
internal class FileRecordingStore @Inject constructor(
    private val files: RecordingFiles,
    private val ids: IdGenerator,
    private val space: StorageSpace,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : RecordingStore {
    private val format = AudioFormat.Dictation

    override suspend fun create(session: SessionId): Outcome<RecordingId, StorageError> = create(session, false)

    /**
     * Creates the file. A caller cancelled meanwhile (a ✕ or a drag while the bubble was arming, or the coordinator's
     * timeout) never learns the new recording's id, so the file it would leave behind (a header-only WAV, found
     * after A8c's quick press-and-drags) is deleted here before the cancellation goes on.
     */
    override suspend fun create(session: SessionId, incognito: Boolean): Outcome<RecordingId, StorageError> {
        var made: RecordingId? = null
        try {
            return withContext(dispatchers.io) {
                createFile(session, incognito).also { if (it is Outcome.Ok) made = it.value }
            }
        } catch (cancelled: CancellationException) {
            made?.let { orphan ->
                val deleted = withContext(NonCancellable) { delete(orphan) }
                if (deleted is Outcome.Err) logger.warn(TAG, null) { "could not delete a cancelled recording file" }
            }
            throw cancelled
        }
    }

    override suspend fun finalise(recording: RecordingId): Outcome<AudioClip, StorageError> {
        val job = currentCoroutineContext()[Job]
        return withContext(dispatchers.io) { finaliseFile(recording) { job?.ensureActive() } }
    }

    override suspend fun delete(recording: RecordingId): Outcome<Unit, StorageError> = withContext(dispatchers.io) {
        val stored = files.find(recording)
        when {
            stored == null -> Outcome.Ok(Unit)
            stored.file.delete() || !stored.file.exists() -> Outcome.Ok(Unit)
            else -> Outcome.Err(StorageFailed)
        }
    }

    override suspend fun findOrphans(owned: Set<RecordingId>): Outcome<List<RecordingId>, StorageError> =
        withContext(dispatchers.io) {
            Outcome.Ok(
                files.all()
                    .filterNot { it.recording in owned || files.isWriting(it.recording) }
                    .map { it.recording },
            )
        }

    private fun createFile(session: SessionId, incognito: Boolean): Outcome<RecordingId, StorageError> {
        val recording = ids.newRecordingId()
        val file = files.fileFor(recording, session, incognito)
        val directory = files.directory
        return when {
            file == null -> Outcome.Err(StorageFailed)
            !directory.isDirectory && !directory.mkdirs() -> Outcome.Err(StorageFailed)
            !makeRoom() -> Outcome.Err(StorageFull)
            else -> writeEmptyRecording(file).map { recording }
        }
    }

    /** Creates [file] (which must not exist yet) holding only a header. */
    private fun writeEmptyRecording(file: File): Outcome<Unit, StorageError> = try {
        if (file.createNewFile()) {
            file.writeBytes(WavHeader.bytes(format, 0))
            Outcome.Ok(Unit)
        } else {
            Outcome.Err(StorageFailed)
        }
    } catch (error: IOException) {
        file.delete()
        logger.warn(TAG, error) { "could not create a recording file" }
        Outcome.Err(if (error.isNoSpace()) StorageFull else StorageFailed)
    }

    private fun finaliseFile(recording: RecordingId, checkCancelled: () -> Unit): Outcome<AudioClip, StorageError> {
        val stored = files.find(recording)
        if (stored == null || files.isWriting(recording)) return Outcome.Err(StorageFailed)
        return try {
            val dataBytes = WavRepair.repair(stored.file, format)
            val detector = SpeechDetector()
            WavSamples.forEachChunk(stored.file, dataBytes) { samples, count ->
                checkCancelled()
                detector.accept(samples, count)
            }
            Outcome.Ok(stored.toClip(dataBytes, format, detector.verdict().toPresence()))
        } catch (error: IOException) {
            logger.warn(TAG, error) { "could not finalise recording ${recording.value}" }
            Outcome.Err(if (error.isNoSpace()) StorageFull else StorageFailed)
        }
    }

    /**
     * Frees space by deleting the oldest recordings when it runs low; false when it stays below the minimum. When
     * the system cannot report the free space nothing is deleted (deleting retained audio needs a real figure); a
     * disk that then fills up shows as a typed write failure.
     */
    private fun makeRoom(): Boolean {
        val free = space.usableBytes(files.directory)
        if (free == null) logger.warn(TAG, null) { "free space unknown; nothing evicted" }
        return free == null || free >= LOW_SPACE_BYTES || evictOldest(free) >= MIN_FREE_BYTES
    }

    /** Deletes the oldest recordings (never the one being written) until [LOW_SPACE_BYTES] are free; the result. */
    private fun evictOldest(initiallyFree: Long): Long {
        var free = initiallyFree
        var deleted = 0
        val oldestFirst = files.all().filterNot { files.isWriting(it.recording) }.iterator()
        while (free < LOW_SPACE_BYTES && oldestFirst.hasNext()) {
            if (oldestFirst.next().file.delete()) {
                deleted++
                free = space.usableBytes(files.directory) ?: LOW_SPACE_BYTES
            }
        }
        if (deleted > 0) logger.info(TAG) { "storage low: deleted $deleted oldest recordings" }
        return free
    }

    companion object {
        /** Below this much free space the oldest recordings are deleted: room for a 6-minute recording and more. */
        const val LOW_SPACE_BYTES: Long = 16L * 1024 * 1024

        /** Below this much free space after eviction a recording is refused: about 30 seconds of audio. */
        const val MIN_FREE_BYTES: Long = 1024L * 1024

        private val TAG = LogTag("RecordingStore")
    }
}
