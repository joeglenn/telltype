package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.Session
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Transition
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.session.idleState
import net.jbglenn.telltype.core.model.speech.Transcript

/** The event does not apply: same state, no effects. */
internal fun SessionState.unchanged(): Transition = Transition(this, emptyList())

/** [this] state with [effects]. */
internal fun SessionState.with(effects: List<SessionEffect>): Transition = Transition(this, effects)

/** [this] state with [effects]. */
internal fun SessionState.with(vararg effects: SessionEffect): Transition = Transition(this, effects.toList())

/** What is focused now, as seen from any state (idle states describe it themselves). */
internal val SessionState.currentFocus: Focus
    get() = when (this) {
        SessionState.Hidden -> Focus.None
        is SessionState.Ready -> Focus.Available(field, mode)
        is SessionState.Unavailable -> Focus.Blocked(reason)
        is SessionState.Arming -> focus
        is SessionState.Listening -> focus
        is SessionState.Working -> focus
        is SessionState.Done -> focus
        is SessionState.Problem -> focus
    }

/** The session of this state, null when there is none. */
internal val SessionState.currentSession: Session?
    get() = when (this) {
        SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Arming -> null
        is SessionState.Listening -> session
        is SessionState.Working -> session
        is SessionState.Done -> session
        is SessionState.Problem -> session
    }

/** The recording this state owns, null when none. */
internal val SessionState.currentRecording: RecordingId?
    get() = when (this) {
        SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Arming -> null
        is SessionState.Done -> null
        is SessionState.Listening -> recording
        is SessionState.Working -> recording
        is SessionState.Problem -> keptAudio?.recording
    }

/** The session ends: the bubble goes to the idle state of the current [focus]. */
internal fun idle(focus: Focus, effects: List<SessionEffect>): Transition = Transition(focus.idleState(), effects)

/**
 * A press where a new recording may start: arming on an available field (after [leaving], the effects of the
 * state being left), the explanation on a blocked one, nothing without a field.
 */
internal fun startRecording(state: SessionState, leaving: List<SessionEffect>): Transition =
    when (val focus = state.currentFocus) {
        is Focus.Available -> Transition(
            SessionState.Arming(focus.field, focus.mode, Trigger.TAP, focus),
            leaving + SessionEffect.StartRecording(focus.field, focus.mode),
        )

        is Focus.Blocked -> state.with(SessionEffect.ShowChip(Chip.Explanation(focus.reason)))

        Focus.None -> state.unchanged()
    }

/** The timer that removes Done's chips: Command Mode's Undo after typing, else the chips. */
internal fun chipTimer(done: SessionState.Done): SessionTimer =
    if (done.session.mode == BubbleMode.COMMAND && done.receipt.via.typed) {
        SessionTimer.COMMAND_UNDO
    } else {
        SessionTimer.DONE_CHIPS
    }

/** A problem with no action but Dismiss closes by itself after `PROBLEM_DISMISS`. */
internal fun dismissesItself(problem: SessionState.Problem): Boolean = problem.actions == setOf(BubbleAction.DISMISS)

/** `SaveHistory`, or nothing for an incognito field. */
internal fun saveHistory(
    session: Session,
    transcript: Transcript?,
    text: CleanedText?,
    insertedVia: InsertedVia,
    error: DictationError?,
): List<SessionEffect> = if (session.field.incognito) {
    emptyList()
} else {
    listOf(SessionEffect.SaveHistory(session, transcript, text, insertedVia, error))
}

/**
 * Enters Problem: [before] (audio handling), the problem haptic, [after] (history), and the self-dismiss timer when
 * Dismiss is the only action.
 */
internal fun enterProblem(
    problem: SessionState.Problem,
    before: List<SessionEffect>,
    after: List<SessionEffect>,
): Transition {
    val timer = SessionEffect.ScheduleTimer(SessionTimer.PROBLEM_DISMISS).takeIf { dismissesItself(problem) }
    return problem.with(before + SessionEffect.Haptic(FeedbackCue.PROBLEM) + after + listOfNotNull(timer))
}
