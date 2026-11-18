package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Transition
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.session.idleState

/**
 * Idle states follow the focus (probing the server when the mic pill appears); in-session states only record it; Done
 * after typing closes when the focus leaves the field the text went to.
 */
internal fun onFocusChanged(state: SessionState, focus: Focus): Transition = when (state) {
    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable -> {
        val next = focus.idleState()
        val appears = state !is SessionState.Ready && next is SessionState.Ready
        next.with(listOfNotNull(SessionEffect.ProbeServerHealth.takeIf { appears }))
    }

    is SessionState.Arming -> state.copy(focus = focus).with()

    is SessionState.Listening -> state.copy(focus = focus).with()

    is SessionState.Working -> state.copy(focus = focus).with()

    is SessionState.Done -> onFocusChangedInDone(state, focus)

    is SessionState.Problem -> state.copy(focus = focus).with()
}

/**
 * Done after typing closes when the focus leaves the field the text went to. Done after a hand-off keeps its chip
 * and runs the chip timer only while a field has the focus.
 */
private fun onFocusChangedInDone(done: SessionState.Done, focus: Focus): Transition {
    val next = done.copy(focus = focus)
    val hadField = done.focus is Focus.Available
    val hasField = focus is Focus.Available
    return when {
        done.handedOff && hasField && !hadField -> next.with(SessionEffect.ScheduleTimer(SessionTimer.DONE_CHIPS))
        done.handedOff && hadField && !hasField -> next.with(SessionEffect.CancelTimer(SessionTimer.DONE_CHIPS))
        done.handedOff -> next.with()
        focus is Focus.Available && focus.field.handle == done.receipt.target.handle -> next.with()
        else -> idle(focus, leaveDone(done))
    }
}

/** A press: starts arming from Ready, Done and Problem; explains when unavailable; busy hint while working. */
internal fun onMicPressed(state: SessionState): Transition = when (state) {
    SessionState.Hidden, is SessionState.Arming, is SessionState.Listening -> state.unchanged()
    is SessionState.Ready -> startRecording(state, emptyList())
    is SessionState.Unavailable -> state.with(SessionEffect.ShowChip(Chip.Explanation(state.reason)))
    is SessionState.Working -> state.with(SessionEffect.ShowChip(Chip.Busy))
    is SessionState.Done -> startRecording(state, leaveDone(state))
    is SessionState.Problem -> startRecording(state, leaveProblem(state))
}

/** The press became a hold, also when audio already flows. */
internal fun onHoldDetected(state: SessionState): Transition = when (state) {
    is SessionState.Arming -> state.copy(trigger = Trigger.HOLD).with()

    is SessionState.Listening -> state.copy(session = state.session.copy(trigger = Trigger.HOLD)).with()

    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Working,
    is SessionState.Done, is SessionState.Problem,
    -> state.unchanged()
}

/** Releasing a hold finishes it (or abandons a start that has not delivered audio); a tap's release is ignored. */
internal fun onMicReleased(state: SessionState): Transition = when (state) {
    is SessionState.Arming ->
        if (state.trigger == Trigger.HOLD) abortStart(state) else state.unchanged()

    is SessionState.Listening ->
        if (state.session.trigger == Trigger.HOLD) finishListening(state) else state.unchanged()

    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Working,
    is SessionState.Done, is SessionState.Problem,
    -> state.unchanged()
}

/** Moving the bubble discards the recording that this press opened. */
internal fun onDragStarted(state: SessionState): Transition = when (state) {
    is SessionState.Arming -> abortStart(state)

    is SessionState.Listening -> cancelListening(state)

    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Working,
    is SessionState.Done, is SessionState.Problem,
    -> state.unchanged()
}

/** ✓ ends the recording. */
internal fun onFinishTapped(state: SessionState): Transition =
    if (state is SessionState.Listening) finishListening(state) else state.unchanged()

/** ✕ cancels whatever runs: the start, the recording, or the processing. */
internal fun onCancelTapped(state: SessionState): Transition = when (state) {
    is SessionState.Arming -> abortStart(state)

    is SessionState.Listening -> cancelListening(state)

    is SessionState.Working -> idle(
        state.focus,
        listOf(SessionEffect.CancelWork(state.session.id), SessionEffect.DeleteRecording(state.recording)),
    )

    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Done,
    is SessionState.Problem,
    -> state.unchanged()
}
