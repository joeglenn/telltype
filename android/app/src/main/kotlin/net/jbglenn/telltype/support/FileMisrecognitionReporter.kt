package net.jbglenn.telltype.support

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.domain.port.MisrecognitionReporter

/**
 * "Report misrecognition" for now (sending to the server is a later package): appends the entry's id, time, app, raw
 * transcript and final text as one JSON line to `files/reports/misrecognitions.jsonl`, which the debug bundle
 * includes. The file stays in app-private storage; nothing of it is logged. Deleting a History entry removes its
 * lines; clearing History deletes the file.
 */
@Singleton
internal class FileMisrecognitionReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : MisrecognitionReporter {
    // Resolved on first use, off the main thread: getFilesDir() touches the disk (StrictMode).
    private val file by lazy { File(context.filesDir, "$DIRECTORY/$FILE_NAME") }
    private val lock = Mutex()

    override suspend fun report(entry: HistoryEntry): Outcome<Unit, DictationError> = withContext(dispatchers.io) {
        val line = buildJsonObject {
            put("session", entry.id.value)
            put("created_at", entry.createdAt.toString())
            put("app", entry.app.name)
            put("raw", entry.rawTranscript)
            put("final", entry.finalText)
        }.toString()
        lock.withLock {
            try {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                Outcome.Ok(Unit)
            } catch (_: IOException) {
                Outcome.Err(StorageFailed)
            }
        }
    }

    override suspend fun forget(id: SessionId): Outcome<Unit, StorageError> = locked {
        if (file.isFile) {
            val kept = file.readLines().filter { it.isNotBlank() && sessionOf(it) != id.value }
            if (kept.isEmpty()) {
                delete()
            } else {
                val next = File(file.parentFile, "$FILE_NAME.next")
                next.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
                if (!next.renameTo(file)) throw IOException("rename failed")
            }
        }
    }

    override suspend fun forgetAll(): Outcome<Unit, StorageError> = locked { delete() }

    /** The report file when it exists (for the debug bundle). */
    fun reportFile(): File? = file.takeIf(File::isFile)

    private suspend fun locked(block: () -> Unit): Outcome<Unit, StorageError> = withContext(dispatchers.io) {
        lock.withLock {
            try {
                block()
                Outcome.Ok(Unit)
            } catch (_: IOException) {
                Outcome.Err(StorageFailed)
            }
        }
    }

    private fun delete() {
        if (file.exists() && !file.delete()) throw IOException("delete failed")
    }

    private companion object {
        const val DIRECTORY = "reports"
        const val FILE_NAME = "misrecognitions.jsonl"

        /** The `session` of one report line; null when the line is not a report. */
        fun sessionOf(line: String): String? = try {
            Json.parseToJsonElement(line).jsonObject["session"]?.jsonPrimitive?.contentOrNull
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
