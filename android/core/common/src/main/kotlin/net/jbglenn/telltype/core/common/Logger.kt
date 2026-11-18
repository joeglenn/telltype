package net.jbglenn.telltype.core.common

/** Severity of a log line, lowest first. */
public enum class LogLevel {
    /** Step-by-step detail; off in release builds. */
    DEBUG,

    /** Normal milestones: session started, stage finished, address chosen. */
    INFO,

    /** Something degraded but the user got a result (cleanup skipped, failover). */
    WARN,

    /** Something failed and the user saw a problem state or a notification. */
    ERROR,
}

/**
 * Names the component a log line comes from, for example `LogTag("Session")`. One constant per class;
 * at most 23 characters so it is also a valid logcat tag.
 */
@JvmInline
public value class LogTag(public val name: String) {
    init {
        require(name.isNotBlank() && name.length <= MAX_LENGTH) { "a log tag is 1 to $MAX_LENGTH characters" }
    }

    private companion object {
        const val MAX_LENGTH = 23
    }
}

/**
 * Wraps dictated text or on-screen context before it goes anywhere near a log message
 * (docs/PLAN.md section 11: logs never contain dictated text or context unless verbose debug is on).
 *
 * Interpolating it prints only its length, for example `"cleaned ${Redacted(text)}"` gives
 * `cleaned ‹redacted, 42 chars›`. Only a [Logger] implementation in verbose-debug mode may call [unredacted].
 */
@JvmInline
public value class Redacted(private val content: String) {
    /** The wrapped text. Call only from a logger sink while verbose debug logging is switched on. */
    public fun unredacted(): String = content

    override fun toString(): String = "‹redacted, ${content.length} chars›"
}

/**
 * The single logging interface (docs/PLAN.md section 13). The app's implementation writes to logcat and to a
 * size-capped rotating file; tests use the recording fake from `:testing`.
 *
 * Messages are lazy: [message] is evaluated only when [level] is enabled, so building a message costs nothing
 * in release. Never put dictated text, on-screen context, the server key or a transcript in a message unless it
 * is wrapped in [Redacted].
 */
public interface Logger {
    /** Whether a line at [level] would be written. */
    public fun isEnabled(level: LogLevel): Boolean

    /** Writes one line; [throwable] is attached with its stack trace when not null. */
    public fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String)
}

/** Logs at [LogLevel.DEBUG]. */
public fun Logger.debug(tag: LogTag, message: () -> String) {
    log(LogLevel.DEBUG, tag, null, message)
}

/** Logs at [LogLevel.INFO]. */
public fun Logger.info(tag: LogTag, message: () -> String) {
    log(LogLevel.INFO, tag, null, message)
}

/** Logs at [LogLevel.WARN], with the cause when there is one. */
public fun Logger.warn(tag: LogTag, throwable: Throwable?, message: () -> String) {
    log(LogLevel.WARN, tag, throwable, message)
}

/** Logs at [LogLevel.ERROR], with the cause when there is one. */
public fun Logger.error(tag: LogTag, throwable: Throwable?, message: () -> String) {
    log(LogLevel.ERROR, tag, throwable, message)
}
