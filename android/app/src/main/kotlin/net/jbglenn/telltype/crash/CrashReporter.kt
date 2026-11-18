package net.jbglenn.telltype.crash

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import net.jbglenn.telltype.BuildConfig
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.logging.LogFile

/**
 * Writes a local crash report (`files/crash/`) when a thread dies with an uncaught exception: the stack, the app
 * and Android versions and the last 200 log lines (which never contain dictated text), then hands the exception to
 * the previous handler so the process dies as usual. Keeps the newest [KEPT_REPORTS] reports.
 */
@Singleton
internal class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: LogFile,
    private val clock: Clock,
) {
    // Resolved on first use, never on the main thread at start-up: getFilesDir() touches the disk.
    private val directory by lazy { File(context.filesDir, DIRECTORY) }

    /** Installs the handler in front of the current default one. Call once, from `Application.onCreate`. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            write(thread, failure)
            previous?.uncaughtException(thread, failure)
        }
    }

    /** The reports on disk, oldest first (for the debug bundle). */
    fun reports(): List<File> = directory.listFiles()?.filter(File::isFile)?.sortedBy(File::getName).orEmpty()

    private fun write(thread: Thread, failure: Throwable) {
        try {
            directory.mkdirs()
            val report = buildString {
                appendLine("Telltype ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MODEL}")
                appendLine("Time ${clock.now()}, thread ${thread.name}")
                appendLine()
                appendLine(Log.getStackTraceString(failure))
                appendLine("Last log lines:")
                log.recentLines().forEach(::appendLine)
            }
            File(directory, "crash-${clock.now().toEpochMilliseconds()}.txt").writeText(report)
            reports().dropLast(KEPT_REPORTS).forEach(File::delete)
        } catch (_: IOException) {
            // Nothing more can be done while the process is dying; the previous handler still runs.
        }
    }

    private companion object {
        const val DIRECTORY = "crash"
        const val KEPT_REPORTS = 5
    }
}
