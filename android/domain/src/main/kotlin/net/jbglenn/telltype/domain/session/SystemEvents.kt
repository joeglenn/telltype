package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.Session
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Transition

/**
 * Audio flows: Arming becomes Listening with both session timers. Anywhere else the start was abandoned, so the
 * recording is cancelled, unless it is the recording this state already owns (a duplicate reply).
 */
internal fun onRecordingStarted(state: SessionState, event: SessionEvent.RecordingStarted): Transition = when {
    state is SessionState.Arming -> SessionState.Listening(
        Session(event.session, state.field, state.mode, state.trigger),
        event.recording,
        event.startedAt,
        state.focus,
    ).with(
        SessionEffect.Haptic(FeedbackCue.RECORDING_STARTED),
        SessionEffect.ScheduleTimer(SessionTimer.SESSION_WARNING),
        SessionEffect.ScheduleTimer(SessionTimer.SESSION_LIMIT),
    )

    state.currentRecording == event.recording -> state.unchanged()

    else -> state.with(SessionEffect.CancelRecording(event.recording))
}

/** The recorder could not start: Problem without a session. */
internal fun onRecordingFailed(state: SessionState, error: CaptureError): Transition =
    if (state is SessionState.Arming) {
        enterProblem(SessionState.Problem(error, null, null, state.focus), emptyList(), emptyList())
    } else {
        state.unchanged()
    }

/** Screen off, call or microphone lost: a recording ends with what was captured; a start is abandoned. */
internal fun onInterrupted(state: SessionState): Transition = when (state) {
    is SessionState.Arming -> abortStart(state)

    is SessionState.Listening -> finishListening(state)

    SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Working,
    is SessionState.Done, is SessionState.Problem,
    -> state.unchanged()
}

/** A timer fired; only the timer the current state owns has an effect. */
internal fun onTimerFired(state: SessionState, timer: SessionTimer): Transition = when {
    state is SessionState.Listening && timer == SessionTimer.SESSION_WARNING ->
        state.with(SessionEffect.ShowChip(Chip.SessionEndingSoon))

    state is SessionState.Listening && timer == SessionTimer.SESSION_LIMIT -> finishListening(state)

    state is SessionState.Done &&
        (timer == chipTimer(state) || (state.handedOff && timer == SessionTimer.HANDOFF_LIMIT)) ->
        idle(state.focus, leaveDone(state).filterNot { it == SessionEffect.CancelTimer(timer) })

    state is SessionState.Problem && timer == SessionTimer.PROBLEM_DISMISS && dismissesItself(state) ->
        idle(state.focus, listOfNotNull(state.keptAudio?.let { SessionEffect.DeleteRecording(it.recording) }))

    else -> state.unchanged()
}

/** A learnable correction is offered where a bubble shows and no session runs: Ready, Unavailable and Done. */
internal fun onCorrectionDetected(state: SessionState, correction: Correction): Transition = when (state) {
    is SessionState.Ready, is SessionState.Unavailable, is SessionState.Done ->
        state.with(SessionEffect.ShowChip(Chip.LearnOffer(correction)))

    SessionState.Hidden, is SessionState.Arming, is SessionState.Listening, is SessionState.Working,
    is SessionState.Problem,
    -> state.unchanged()
}

/** The user accepted "Learn X → Y": store it, whatever the bubble does now. */
internal fun onLearnAccepted(state: SessionState, correction: Correction): Transition =
    state.with(SessionEffect.SaveCorrection(correction))
