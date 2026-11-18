package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer

/**
 * Whether [this] Done follows a hand-off (Paste or Copy chip): its chips stay until a field has had the focus for
 * `DONE_CHIPS` (the timer runs only while a field has the focus), at most `HANDOFF_LIMIT` after the hand-off.
 */
internal val SessionState.Done.handedOff: Boolean
    get() = !receipt.via.typed

/** The timers a Done starts with: the chip timer, or for a hand-off the limit and, with a field focused, the chips. */
internal fun doneTimers(done: SessionState.Done): List<SessionEffect> = if (done.handedOff) {
    listOfNotNull(
        SessionEffect.ScheduleTimer(SessionTimer.HANDOFF_LIMIT),
        SessionEffect.ScheduleTimer(SessionTimer.DONE_CHIPS).takeIf { done.focus is Focus.Available },
    )
} else {
    listOf(SessionEffect.ScheduleTimer(chipTimer(done)))
}

/** Leaving [done] some other way than through its timers: cancels them. */
internal fun leaveDone(done: SessionState.Done): List<SessionEffect> = listOfNotNull(
    SessionEffect.CancelTimer(chipTimer(done)),
    SessionEffect.CancelTimer(SessionTimer.HANDOFF_LIMIT).takeIf { done.handedOff },
)
