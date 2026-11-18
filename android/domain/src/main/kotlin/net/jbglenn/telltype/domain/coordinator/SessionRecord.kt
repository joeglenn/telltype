package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.Deferred
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/** The category and tone a session was recorded with (resolved once, at record start). */
internal data class SessionStyle(val category: AppCategory, val tone: Tone)

/**
 * What the coordinator knows about one session beyond the reducer's state: its context (held in memory for this
 * dictation only, never logged), style, start time and stage timings on the monotonic clock. Confined to the
 * coordinator's dispatcher.
 */
internal class SessionRecord(val id: SessionId, val field: FieldTarget, val mode: BubbleMode, val style: SessionStyle) {
    /** The context read started at record start; `Clean` waits for it. */
    var context: Deferred<FieldContext>? = null

    /** The recording, once audio flows. */
    var recording: RecordingId? = null

    /** Wall-clock time audio started (the history row's creation time). */
    var startedAt: Instant? = null

    /** Monotonic time audio started. */
    var openedAt: Duration? = null

    /** Monotonic time the recording was stopped (the start of the end-to-end latency). */
    var stoppedAt: Duration? = null

    /** The finished clip's length. */
    var audioDuration: Duration? = null

    /** The stages measured so far. */
    var timings: SessionTimings = SessionTimings.None

    /** Appends the server's `Server-Timing` entries. */
    fun addServerTimings(entries: List<ServerTiming>) {
        timings = timings.copy(server = timings.server + entries)
    }

    /** Adds [spent] to the cleanup stage (a fallback after a failed model call adds to the same stage). */
    fun addCleanup(spent: Duration) {
        timings = timings.copy(cleanup = (timings.cleanup ?: Duration.ZERO) + spent)
    }

    /** Records the insertion (or hand-off) from [started] to [now], and the end-to-end latency. */
    fun finishInsertion(started: Duration, now: Duration) {
        timings = timings.copy(insertion = now - started, endToEnd = stoppedAt?.let { now - it })
    }

    override fun toString(): String = "SessionRecord(id=$id, mode=$mode, style=$style)"
}
