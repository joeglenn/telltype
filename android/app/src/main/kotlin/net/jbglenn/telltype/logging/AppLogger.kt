package net.jbglenn.telltype.logging

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger

/**
 * The one [Logger]: logcat plus the rotating [LogFile]. Info and above always; debug only while verbose logging is
 * switched on in Settings ([verboseUntil], kept in step by `AppStartup`). Messages are built lazily and only when
 * the level is enabled; they carry `Redacted` wrappers, never dictated text or the key (docs/PLAN.md section 13).
 */
@Singleton
internal class AppLogger @Inject constructor(private val file: LogFile, private val clock: Clock) : Logger {
    /** Verbose logging ends at this time; null = off. */
    @Volatile
    var verboseUntil: Instant? = null

    override fun isEnabled(level: LogLevel): Boolean =
        level >= LogLevel.INFO || verboseUntil?.let { clock.now() < it } == true

    override fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String) {
        if (!isEnabled(level)) return
        val text = message()
        val trace = throwable?.let { "\n" + Log.getStackTraceString(it) }.orEmpty()
        Log.println(priorityOf(level), tag.name, text + trace)
        file.append("${clock.now()} ${level.name.first()} ${tag.name}: $text$trace")
    }

    private fun priorityOf(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }
}
