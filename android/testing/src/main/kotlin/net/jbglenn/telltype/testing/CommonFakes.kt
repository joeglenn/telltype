package net.jbglenn.telltype.testing

import kotlin.time.Duration
import kotlin.time.Instant
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId

/**
 * A clock that moves only when the test says so. Wall and monotonic time advance together.
 *
 * @param start the initial wall-clock time.
 */
public class FixedClock(start: Instant = Fixtures.Epoch) : Clock {
    private var wall: Instant = start
    private var elapsed: Duration = Duration.ZERO

    override fun now(): Instant = wall

    override fun monotonic(): Duration = elapsed

    /** Moves both clocks forward by [step] (`>= 0`). */
    public fun advanceBy(step: Duration) {
        require(!step.isNegative()) { "time only moves forward" }
        wall += step
        elapsed += step
    }
}

/** Ids `session-1`, `session-2`, ... and `recording-1`, `recording-2`, ..., counted separately. */
public class SequentialIdGenerator : IdGenerator {
    private var sessions = 0
    private var recordings = 0

    override fun newSessionId(): SessionId = SessionId("session-${++sessions}")

    override fun newRecordingId(): RecordingId = RecordingId("recording-${++recordings}")
}

/**
 * One line written to [RecordingLogger].
 *
 * @property level the level.
 * @property tag the tag.
 * @property message the evaluated message.
 * @property throwable the attached cause, if any.
 */
public data class LogEntry(
    public val level: LogLevel,
    public val tag: LogTag,
    public val message: String,
    public val throwable: Throwable?,
)

/**
 * Keeps every line in memory so tests can assert what was logged, and what was not (no dictated text).
 *
 * @param minimum lines below this level are disabled and their message is never evaluated.
 */
public class RecordingLogger(private val minimum: LogLevel = LogLevel.DEBUG) : Logger {
    private val lines = mutableListOf<LogEntry>()

    /** Every line written so far, oldest first. */
    public val entries: List<LogEntry> get() = lines.toList()

    override fun isEnabled(level: LogLevel): Boolean = level >= minimum

    override fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String) {
        if (isEnabled(level)) lines += LogEntry(level, tag, message(), throwable)
    }
}
