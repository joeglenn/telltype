package net.jbglenn.telltype.domain.coordinator

import java.util.EnumMap
import kotlin.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.model.session.SessionTimer

/**
 * The reducer's timers, one job per `SessionTimer` (contracts page 3: durations owned by the coordinator).
 * Scheduling a running timer restarts it. Each scheduling has a generation, so an expiry that was already queued
 * when the timer was cancelled or restarted is dropped instead of firing early.
 */
internal class TimerBank(private val ctx: CoordinatorContext) {
    private val running = EnumMap<SessionTimer, Pair<Long, Job>>(SessionTimer::class.java)
    private var generation = 0L

    /** Starts (or restarts) [timer]. */
    fun schedule(timer: SessionTimer) {
        cancel(timer)
        val ticket = ++generation
        val duration = durationOf(timer)
        running[timer] = ticket to ctx.work.launch {
            delay(duration)
            ctx.send(Inbound.TimerExpired(timer, ticket))
        }
    }

    /** Stops [timer] if it runs. */
    fun cancel(timer: SessionTimer) {
        running.remove(timer)?.second?.cancel()
    }

    /** Whether [expired] is the latest scheduling of its timer; if so the timer is no longer running. */
    fun accept(expired: Inbound.TimerExpired): Boolean {
        val live = running[expired.timer]?.first == expired.generation
        if (live) running.remove(expired.timer)
        return live
    }

    private fun durationOf(timer: SessionTimer): Duration {
        val limit = ctx.settings.dictation.sessionLimit
        return when (timer) {
            SessionTimer.SESSION_WARNING -> (limit - ctx.config.sessionWarningLead).coerceAtLeast(Duration.ZERO)
            SessionTimer.SESSION_LIMIT -> limit
            SessionTimer.DONE_CHIPS -> ctx.config.doneChips
            SessionTimer.COMMAND_UNDO -> ctx.config.commandUndo
            SessionTimer.PROBLEM_DISMISS -> ctx.config.problemDismiss
            SessionTimer.HANDOFF_LIMIT -> ctx.config.handoffLimit
        }
    }
}
