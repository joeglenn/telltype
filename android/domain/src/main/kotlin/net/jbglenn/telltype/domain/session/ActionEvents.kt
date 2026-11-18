package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Transition

/** A chip or button; ignored when the state does not offer [action]. */
internal fun onActionTapped(state: SessionState, action: BubbleAction): Transition = when (state) {
    is SessionState.Unavailable ->
        if (action == BubbleAction.FIX) state.with(SessionEffect.OpenFix(state.reason.fix)) else state.unchanged()

    is SessionState.Done -> if (action in state.actions) onDoneAction(state, action) else state.unchanged()

    is SessionState.Problem -> if (action in state.actions) onProblemAction(state, action) else state.unchanged()

    SessionState.Hidden, is SessionState.Ready, is SessionState.Arming, is SessionState.Listening,
    is SessionState.Working,
    -> state.unchanged()
}

private fun onDoneAction(done: SessionState.Done, action: BubbleAction): Transition = when (action) {
    BubbleAction.RAW -> done.rawAlternative?.let { raw ->
        done.with(SessionEffect.ReplaceInserted(done.session.id, done.receipt, raw))
    } ?: done.unchanged()

    BubbleAction.UNDO -> idle(
        done.focus,
        listOf(
            SessionEffect.CancelTimer(chipTimer(done)),
            SessionEffect.ReplaceInserted(done.session.id, done.receipt, done.receipt.replaced),
        ),
    )

    BubbleAction.PASTE -> when (val focus = done.focus) {
        is Focus.Available -> done.with(SessionEffect.Insert(done.session.id, focus.field, done.receipt.text))
        Focus.None, is Focus.Blocked -> done.unchanged()
    }

    BubbleAction.COPY -> done.with(SessionEffect.CopyToClipboard(done.receipt.text))

    BubbleAction.RETRY, BubbleAction.FIX, BubbleAction.DISMISS -> done.unchanged()
}

private fun onProblemAction(problem: SessionState.Problem, action: BubbleAction): Transition = when (action) {
    BubbleAction.RETRY -> retry(problem)
    BubbleAction.FIX -> problem.error.fix?.let { problem.with(SessionEffect.OpenFix(it)) } ?: problem.unchanged()
    BubbleAction.DISMISS -> idle(problem.focus, leaveProblem(problem))
    BubbleAction.COPY, BubbleAction.PASTE, BubbleAction.RAW, BubbleAction.UNDO -> problem.unchanged()
}

/** Retry transcribes the kept audio again, or records anew when none is kept. */
private fun retry(problem: SessionState.Problem): Transition {
    val session = problem.session
    val clip = problem.keptAudio
    return if (session != null && clip != null) {
        SessionState.Working(session, clip.recording, Stage.Transcribing(clip), problem.focus)
            .with(SessionEffect.Transcribe(session, clip))
    } else {
        startRecording(problem, leaveProblem(problem))
    }
}
