package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Transition

/* The ways of leaving Arming, Listening and Problem, shared by several events. */

/** The effects of leaving a Problem other than by its timer: stop the timer, delete kept audio. */
internal fun leaveProblem(problem: SessionState.Problem): List<SessionEffect> = listOfNotNull(
    SessionEffect.CancelTimer(SessionTimer.PROBLEM_DISMISS).takeIf { dismissesItself(problem) },
    problem.keptAudio?.let { SessionEffect.DeleteRecording(it.recording) },
)

/** Abandons a start that has not delivered audio yet. */
internal fun abortStart(arming: SessionState.Arming): Transition =
    idle(arming.focus, listOf(SessionEffect.AbortRecordingStart))

/** Recording ends normally: both session timers stop and the file is finalised. */
internal fun finishListening(listening: SessionState.Listening): Transition = SessionState.Working(
    listening.session,
    listening.recording,
    Stage.Finalising,
    listening.focus,
).with(
    SessionEffect.CancelTimer(SessionTimer.SESSION_WARNING),
    SessionEffect.CancelTimer(SessionTimer.SESSION_LIMIT),
    SessionEffect.Haptic(FeedbackCue.RECORDING_STOPPED),
    SessionEffect.StopRecording(listening.session.id, listening.recording),
)

/** Recording discarded: both session timers stop and the file is deleted. */
internal fun cancelListening(listening: SessionState.Listening): Transition = idle(
    listening.focus,
    listOf(
        SessionEffect.CancelTimer(SessionTimer.SESSION_WARNING),
        SessionEffect.CancelTimer(SessionTimer.SESSION_LIMIT),
        SessionEffect.CancelRecording(listening.recording),
    ),
)
