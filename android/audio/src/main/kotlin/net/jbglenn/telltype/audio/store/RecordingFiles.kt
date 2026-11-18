package net.jbglenn.telltype.audio.store

import java.io.File
import java.util.Collections
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId

/**
 * One recording file on disk: `<recording>.<session>.wav`, or `<recording>.<session>.incognito.wav` for a recording
 * made for an incognito field, in [RecordingFiles.directory].
 *
 * @property recording the recording id (first part of the name).
 * @property session the session it belongs to (second part), so a recovered file still knows its session.
 * @property file the file.
 * @property incognito whether the name carries the incognito mark, so a recovered file is never saved to History.
 */
internal data class StoredRecording(
    val recording: RecordingId,
    val session: SessionId,
    val file: File,
    val incognito: Boolean = false,
)

/**
 * The naming and the location of recording files, shared by the store and the recorder. The directory is
 * app-private and excluded from backup (`Context.noBackupFilesDir`, docs/PLAN.md section 11); audio is never
 * written anywhere else. Ids must match [ID_PATTERN], so an id can never name a path outside the directory.
 *
 * Also tracks which recording the recorder is writing right now, so the store's low-space eviction never deletes it.
 *
 * @property directory where every recording file lives.
 */
internal class RecordingFiles(val directory: File) {
    private val writing: MutableSet<RecordingId> = Collections.synchronizedSet(mutableSetOf())

    /** The file for a new recording ([incognito]: with the mark), or null when an id is not a safe file name part. */
    fun fileFor(recording: RecordingId, session: SessionId, incognito: Boolean = false): File? {
        val mark = if (incognito) "$SEPARATOR$INCOGNITO" else ""
        return if (isSafe(recording.value) && isSafe(session.value)) {
            File(directory, "${recording.value}$SEPARATOR${session.value}$mark$EXTENSION")
        } else {
            null
        }
    }

    /** The stored file of [recording], or null when there is none. */
    fun find(recording: RecordingId): StoredRecording? =
        if (isSafe(recording.value)) all().firstOrNull { it.recording == recording } else null

    /** Every recording file in the directory, oldest first (by modification time). Other files are ignored. */
    fun all(): List<StoredRecording> {
        val files = directory.listFiles() ?: return emptyList()
        return files.mapNotNull(::parse).sortedBy { it.file.lastModified() }
    }

    /** Marks [recording] as being written (true) or not (false). */
    fun markWriting(recording: RecordingId, active: Boolean) {
        if (active) writing += recording else writing -= recording
    }

    /** Whether the recorder is writing [recording] right now. */
    fun isWriting(recording: RecordingId): Boolean = recording in writing

    private fun parse(file: File): StoredRecording? {
        val name = file.name
        val parts = name.removeSuffix(EXTENSION).split(SEPARATOR)
        val incognito = parts.size == MARKED_PARTS && parts.last() == INCOGNITO
        val ours = file.isFile && name.endsWith(EXTENSION) && (parts.size == 2 || incognito) && parts.all(::isSafe)
        return if (ours) StoredRecording(RecordingId(parts[0]), SessionId(parts[1]), file, incognito) else null
    }

    private fun isSafe(id: String): Boolean = ID_PATTERN.matches(id)

    companion object {
        /** Letters, digits, `-` and `_`, 1 to 64 characters: UUIDs and the test ids. */
        val ID_PATTERN: Regex = Regex("[A-Za-z0-9_-]{1,64}")

        /** Name of the recordings directory inside `noBackupFilesDir`. */
        const val DIRECTORY_NAME: String = "recordings"

        private const val SEPARATOR = "."
        private const val EXTENSION = ".wav"
        private const val INCOGNITO = "incognito"
        private const val MARKED_PARTS = 3
    }
}
