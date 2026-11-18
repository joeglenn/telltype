package net.jbglenn.telltype.logging

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's log file in private storage (`files/logs`): one current file rotated to one old file at [MAX_BYTES], so
 * at most twice that is kept. Writes run in order on one background thread; the last [RECENT_LINES] lines are also
 * kept in memory for the crash report. Callers have already left out dictated text and the key.
 */
@Singleton
internal class LogFile @Inject constructor(@ApplicationContext private val context: Context) {
    // Resolved on the writer thread: getFilesDir() touches the disk (StrictMode).
    private val directory by lazy { File(context.filesDir, DIRECTORY) }
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, THREAD_NAME).apply { isDaemon = true }
    }
    private val recent = ArrayDeque<String>(RECENT_LINES)

    /** Queues [line] for the file and keeps it in memory. Never blocks on disk, never throws. */
    fun append(line: String) {
        synchronized(recent) {
            if (recent.size == RECENT_LINES) recent.removeFirst()
            recent.addLast(line)
        }
        writer.execute { write(line) }
    }

    /** The last lines logged in this process, oldest first. */
    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    /** The log files that exist now, oldest first (for the debug bundle). */
    fun files(): List<File> = listOf(File(directory, OLD_FILE), File(directory, CURRENT_FILE)).filter(File::isFile)

    private fun write(line: String) {
        try {
            directory.mkdirs()
            val current = File(directory, CURRENT_FILE)
            if (current.length() > MAX_BYTES) current.renameTo(File(directory, OLD_FILE))
            FileWriter(current, true).use { it.append(line).append('\n') }
        } catch (_: IOException) {
            // The log file is best effort: a full disk must not take logging (or the app) down with it.
        }
    }

    companion object {
        /** Lines kept in memory for the crash report. */
        const val RECENT_LINES = 200

        /** Size at which the current file is rotated. */
        const val MAX_BYTES = 256L * 1024L

        private const val DIRECTORY = "logs"
        private const val CURRENT_FILE = "app.log"
        private const val OLD_FILE = "app.log.1"
        private const val THREAD_NAME = "telltype-log"
    }
}
