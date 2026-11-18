package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEffect.CancelTimer
import net.jbglenn.telltype.core.model.session.SessionEffect.Haptic
import net.jbglenn.telltype.core.model.session.SessionEffect.ScheduleTimer
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Transition
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.testing.Fixtures

private val w = ReducerWorld()
private val history = SessionEffect.SaveHistory(w.session, null, null, InsertedVia.NONE, null)

private fun row(
    name: String,
    state: SessionState,
    event: SessionEvent,
    next: SessionState,
    vararg effects: SessionEffect,
) = Row(name, state, event, Transition(next, effects.toList()))

/** Rows of the table (idle states). */
val IdleRows: List<Row> = listOf(
    row(
        "Hidden + focus available shows Ready and probes",
        SessionState.Hidden,
        focus(w.available),
        w.ready,
        SessionEffect.ProbeServerHealth,
    ),
    row(
        "Unavailable + focus available probes",
        w.unavailable,
        focus(w.available),
        w.ready,
        SessionEffect.ProbeServerHealth,
    ),
    row(
        "Ready + focus on another field: no probe",
        w.ready,
        focus(w.otherFocus),
        SessionState.Ready(w.otherField, BubbleMode.DICTATE),
    ),
    row("Ready + focus none hides", w.ready, focus(Focus.None), SessionState.Hidden),
    row("Ready + focus blocked", w.ready, focus(w.blocked), w.unavailable),
    row("Hidden + focus blocked", SessionState.Hidden, focus(w.blocked), w.unavailable),
    row(
        "Ready + press arms",
        w.ready,
        SessionEvent.MicPressed,
        w.armingTap,
        SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
    ),
    row(
        "Ready in command mode + press arms a command",
        SessionState.Ready(w.field, BubbleMode.COMMAND),
        SessionEvent.MicPressed,
        SessionState.Arming(w.field, BubbleMode.COMMAND, Trigger.TAP, Focus.Available(w.field, BubbleMode.COMMAND)),
        SessionEffect.StartRecording(w.field, BubbleMode.COMMAND),
    ),
    row(
        "Unavailable + press explains",
        w.unavailable,
        SessionEvent.MicPressed,
        w.unavailable,
        SessionEffect.ShowChip(Chip.Explanation(UnavailableReason.SERVER_NOT_CONFIGURED)),
    ),
    row(
        "Unavailable + Fix opens setup",
        w.unavailable,
        action(BubbleAction.FIX),
        w.unavailable,
        SessionEffect.OpenFix(UnavailableReason.SERVER_NOT_CONFIGURED.fix),
    ),
    row(
        "Ready + correction offers learning",
        w.ready,
        SessionEvent.CorrectionDetected(w.correction),
        w.ready,
        SessionEffect.ShowChip(Chip.LearnOffer(w.correction)),
    ),
    row(
        "Hidden + learn accepted saves",
        SessionState.Hidden,
        SessionEvent.LearnAccepted(w.correction),
        SessionState.Hidden,
        SessionEffect.SaveCorrection(w.correction),
    ),
    row(
        "Ready + stray RecordingStarted is cancelled",
        w.ready,
        started(),
        w.ready,
        SessionEffect.CancelRecording(RecordingId("recording-2")),
    ),
)

/** Rows of the table (arming states). */
val ArmingRows: List<Row> = listOf(
    row(
        "Arming + RecordingStarted listens",
        w.armingTap,
        SessionEvent.RecordingStarted(w.sessionId, w.recording, Fixtures.Epoch),
        w.listeningTap,
        Haptic(FeedbackCue.RECORDING_STARTED),
        ScheduleTimer(SessionTimer.SESSION_WARNING),
        ScheduleTimer(SessionTimer.SESSION_LIMIT),
    ),
    row(
        "Arming(hold) + RecordingStarted keeps the hold",
        w.armingHold,
        SessionEvent.RecordingStarted(w.sessionId, w.recording, Fixtures.Epoch),
        w.listeningHold,
        Haptic(FeedbackCue.RECORDING_STARTED),
        ScheduleTimer(SessionTimer.SESSION_WARNING),
        ScheduleTimer(SessionTimer.SESSION_LIMIT),
    ),
    row(
        "Arming + recorder failed (retryable): no dismiss timer",
        w.armingTap,
        SessionEvent.RecordingFailed(RecorderFailed),
        SessionState.Problem(RecorderFailed, null, null, w.available),
        Haptic(FeedbackCue.PROBLEM),
    ),
    row(
        "Arming + microphone in use (Dismiss only): dismiss timer",
        w.armingTap,
        SessionEvent.RecordingFailed(MicrophoneInUse),
        SessionState.Problem(MicrophoneInUse, null, null, w.available),
        Haptic(FeedbackCue.PROBLEM),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row("Arming + hold detected", w.armingTap, SessionEvent.HoldDetected, w.armingHold),
    row(
        "Arming(hold) + release abandons",
        w.armingHold,
        SessionEvent.MicReleased,
        w.ready,
        SessionEffect.AbortRecordingStart,
    ),
    row(
        "Arming + cancel abandons",
        w.armingTap,
        SessionEvent.CancelTapped,
        w.ready,
        SessionEffect.AbortRecordingStart,
    ),
    row(
        "Arming + drag abandons",
        w.armingTap,
        SessionEvent.DragStarted,
        w.ready,
        SessionEffect.AbortRecordingStart,
    ),
    row(
        "Arming + interruption abandons",
        w.armingTap,
        SessionEvent.Interrupted(Interruption.SCREEN_OFF),
        w.ready,
        SessionEffect.AbortRecordingStart,
    ),
    row(
        "Arming + focus lost: abandon goes Hidden later",
        w.armingTap,
        focus(Focus.None),
        w.armingTap.copy(focus = Focus.None),
    ),
)

private val stopEffects = arrayOf<SessionEffect>(
    CancelTimer(SessionTimer.SESSION_WARNING),
    CancelTimer(SessionTimer.SESSION_LIMIT),
    Haptic(FeedbackCue.RECORDING_STOPPED),
    SessionEffect.StopRecording(w.sessionId, w.recording),
)
private val cancelEffects = arrayOf<SessionEffect>(
    CancelTimer(SessionTimer.SESSION_WARNING),
    CancelTimer(SessionTimer.SESSION_LIMIT),
    SessionEffect.CancelRecording(w.recording),
)

/** Rows of the table (listening states). */
val ListeningRows: List<Row> = listOf(
    row("Listening + finish", w.listeningTap, SessionEvent.FinishTapped, w.finalising, *stopEffects),
    row(
        "Listening(hold) + release finishes",
        w.listeningHold,
        SessionEvent.MicReleased,
        w.finalising.copy(session = w.listeningHold.session),
        *stopEffects,
    ),
    row(
        "Listening + session limit finishes",
        w.listeningTap,
        timer(SessionTimer.SESSION_LIMIT),
        w.finalising,
        *stopEffects,
    ),
    row(
        "Listening + call finishes",
        w.listeningTap,
        SessionEvent.Interrupted(Interruption.CALL_STARTED),
        w.finalising,
        *stopEffects,
    ),
    row(
        "Listening + warning",
        w.listeningTap,
        timer(SessionTimer.SESSION_WARNING),
        w.listeningTap,
        SessionEffect.ShowChip(Chip.SessionEndingSoon),
    ),
    row("Listening + cancel", w.listeningTap, SessionEvent.CancelTapped, w.ready, *cancelEffects),
    row("Listening + drag", w.listeningTap, SessionEvent.DragStarted, w.ready, *cancelEffects),
    row("Listening + late hold detection", w.listeningTap, SessionEvent.HoldDetected, w.listeningHold),
    row(
        "Listening + another recording is cancelled",
        w.listeningTap,
        started(),
        w.listeningTap,
        SessionEffect.CancelRecording(RecordingId("recording-2")),
    ),
)

/** Rows of the table (working states). */
val WorkingRows: List<Row> = listOf(
    row(
        "Finalising + audio",
        w.finalising,
        SessionEvent.AudioFinalised(w.sessionId, w.clip),
        w.transcribing,
        SessionEffect.Transcribe(w.session, w.clip),
    ),
    row(
        "Finalising + audio failed (keeps audio): left for the orphan scan",
        w.finalising,
        SessionEvent.AudioFailed(w.sessionId, StorageFailed),
        SessionState.Problem(StorageFailed, w.session, null, w.available),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = StorageFailed),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Finalising + no speech: audio deleted",
        w.finalising,
        SessionEvent.AudioFailed(w.sessionId, NoSpeech),
        w.problemDismiss,
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = NoSpeech),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Transcribing + transcript: history, then clean",
        w.transcribing,
        SessionEvent.Transcribed(w.sessionId, w.transcript),
        w.cleaning,
        history.copy(transcript = w.transcript),
        SessionEffect.Clean(w.session, w.transcript),
    ),
    row(
        "Transcribing (faint clip) + transcript: no speech",
        w.transcribing.copy(stage = Stage.Transcribing(w.faintClip)),
        SessionEvent.Transcribed(w.sessionId, w.transcript),
        w.problemDismiss,
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = NoSpeech),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Transcribing + unreachable: queued",
        w.transcribing,
        SessionEvent.TranscriptionFailed(w.sessionId, ServerUnreachable),
        SessionState.Problem(ServerUnreachable, w.session, null, w.available),
        SessionEffect.EnqueuePendingJob(w.session, w.clip),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = ServerUnreachable),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Transcribing + empty transcript: audio kept for Retry",
        w.transcribing,
        SessionEvent.TranscriptionFailed(w.sessionId, EmptyTranscript),
        w.problemKept,
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = EmptyTranscript),
    ),
    row(
        "Transcribing + request rejected (retryable, keeps audio)",
        w.transcribing,
        SessionEvent.TranscriptionFailed(w.sessionId, RequestRejected(413, "too_large")),
        SessionState.Problem(RequestRejected(413, "too_large"), w.session, w.clip, w.available),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = RequestRejected(413, "too_large")),
    ),
    row(
        "Transcribing + no speech: deleted",
        w.transcribing,
        SessionEvent.TranscriptionFailed(w.sessionId, NoSpeech),
        w.problemDismiss,
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(error = NoSpeech),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Cleaning + text: insert into the target",
        w.cleaning,
        SessionEvent.Cleaned(w.sessionId, w.cleaned),
        w.inserting,
        SessionEffect.Insert(w.sessionId, w.field, w.cleaned.text),
    ),
    row(
        "Cleaning + blank text: no speech",
        w.cleaning,
        SessionEvent.Cleaned(w.sessionId, Fixtures.cleanedText(text = " ")),
        w.problemDismiss,
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.PROBLEM),
        history.copy(transcript = w.transcript, error = NoSpeech),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Cleaning + cleanup timeout (dictation): rules",
        w.cleaning,
        SessionEvent.CleanupFailed(w.sessionId, CleanupTimedOut),
        w.cleaning,
        SessionEffect.CleanWithRules(w.session, w.transcript, CleanupStatus.SKIPPED_TIMEOUT),
    ),
    row(
        "Cleaning + cleanup failed (command): problem",
        w.cleaning.copy(session = w.commandSession),
        SessionEvent.CleanupFailed(w.sessionId, KeyRejected),
        SessionState.Problem(KeyRejected, w.commandSession, null, w.available),
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.PROBLEM),
        SessionEffect.SaveHistory(w.commandSession, w.transcript, null, InsertedVia.NONE, KeyRejected),
    ),
    row(
        "Inserting + inserted: Done with Raw",
        w.inserting,
        SessionEvent.Inserted(w.sessionId, w.receipt),
        w.doneTyped,
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.DONE),
        SessionEffect.SaveHistory(w.session, w.transcript, w.cleaned, InsertedVia.INPUT_CONNECTION, null),
        ScheduleTimer(SessionTimer.DONE_CHIPS),
        SessionEffect.WatchFieldEdits(w.receipt),
    ),
    row(
        "Inserting (command) + inserted: Undo for 10 s, no learning",
        w.inserting.copy(session = w.commandSession),
        SessionEvent.Inserted(w.sessionId, w.receipt),
        SessionState.Done(w.commandSession, w.receipt, null, w.available),
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.DONE),
        SessionEffect.SaveHistory(w.commandSession, w.transcript, w.cleaned, InsertedVia.INPUT_CONNECTION, null),
        ScheduleTimer(SessionTimer.COMMAND_UNDO),
    ),
    row(
        "Inserting + insertion failed: hand off",
        w.inserting,
        SessionEvent.InsertionFailed(w.sessionId, FieldGone),
        w.inserting,
        SessionEffect.HandOff(w.session, w.cleaned.text),
    ),
    row(
        "Inserting + handed off: Done with Paste",
        w.inserting,
        SessionEvent.HandedOff(w.sessionId, w.handoffReceipt),
        w.doneHandoff,
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.DONE),
        SessionEffect.SaveHistory(w.session, w.transcript, w.cleaned, InsertedVia.HANDOFF, null),
        ScheduleTimer(SessionTimer.HANDOFF_LIMIT),
        ScheduleTimer(SessionTimer.DONE_CHIPS),
    ),
    row(
        "Inserting + handed off with no field focused: the chip waits for a field",
        w.inserting.copy(focus = Focus.None),
        SessionEvent.HandedOff(w.sessionId, w.handoffReceipt),
        w.doneHandoff.copy(focus = Focus.None),
        SessionEffect.DeleteRecording(w.recording),
        Haptic(FeedbackCue.DONE),
        SessionEffect.SaveHistory(w.session, w.transcript, w.cleaned, InsertedVia.HANDOFF, null),
        ScheduleTimer(SessionTimer.HANDOFF_LIMIT),
    ),
    row(
        "Working + press: busy",
        w.cleaning,
        SessionEvent.MicPressed,
        w.cleaning,
        SessionEffect.ShowChip(Chip.Busy),
    ),
    row(
        "Working + cancel",
        w.cleaning,
        SessionEvent.CancelTapped,
        w.ready,
        SessionEffect.CancelWork(w.sessionId),
        SessionEffect.DeleteRecording(w.recording),
    ),
    row(
        "Working + focus moves: recorded only",
        w.cleaning,
        focus(w.otherFocus),
        w.cleaning.copy(focus = w.otherFocus),
    ),
)

/** Rows of the table (done states). */
val DoneRows: List<Row> = listOf(
    row("Done + chips timer", w.doneTyped, timer(SessionTimer.DONE_CHIPS), w.ready),
    row("Done(command) + undo timer", w.doneCommand, timer(SessionTimer.COMMAND_UNDO), w.ready),
    row(
        "Done + Raw",
        w.doneTyped,
        action(BubbleAction.RAW),
        w.doneTyped,
        SessionEffect.ReplaceInserted(w.sessionId, w.receipt, w.transcript.text),
    ),
    row(
        "Done + Undo",
        w.doneTyped,
        action(BubbleAction.UNDO),
        w.ready,
        CancelTimer(SessionTimer.DONE_CHIPS),
        SessionEffect.ReplaceInserted(w.sessionId, w.receipt, ""),
    ),
    row(
        "Done(command) + Undo restores the selection",
        w.doneCommand,
        action(BubbleAction.UNDO),
        w.ready,
        CancelTimer(SessionTimer.COMMAND_UNDO),
        SessionEffect.ReplaceInserted(w.sessionId, w.doneCommand.receipt, "old text"),
    ),
    row(
        "Done(handoff) + Paste into the focused field",
        w.doneHandoff.copy(focus = w.otherFocus),
        action(BubbleAction.PASTE),
        w.doneHandoff.copy(focus = w.otherFocus),
        SessionEffect.Insert(w.sessionId, w.otherField, w.handoffReceipt.text),
    ),
    row(
        "Done(none) + Copy",
        w.doneNone,
        action(BubbleAction.COPY),
        w.doneNone,
        SessionEffect.CopyToClipboard(w.noneReceipt.text),
    ),
    row(
        "Done(typed) + focus leaves: idle",
        w.doneTyped,
        focus(w.otherFocus),
        SessionState.Ready(w.otherField, BubbleMode.DICTATE),
        CancelTimer(SessionTimer.DONE_CHIPS),
    ),
    row(
        "Done(handoff) + focus moves: Paste stays",
        w.doneHandoff,
        focus(w.otherFocus),
        w.doneHandoff.copy(focus = w.otherFocus),
    ),
    row(
        "Done(handoff) without a field + a field gets the focus: the chip timer starts",
        w.doneHandoff.copy(focus = Focus.None),
        focus(w.available),
        w.doneHandoff,
        ScheduleTimer(SessionTimer.DONE_CHIPS),
    ),
    row(
        "Done(handoff) + the field loses the focus: the chip timer stops, Paste stays",
        w.doneHandoff,
        focus(Focus.None),
        w.doneHandoff.copy(focus = Focus.None),
        CancelTimer(SessionTimer.DONE_CHIPS),
    ),
    row(
        "Done(handoff) + chips timer (a field had the focus for 5 s)",
        w.doneHandoff,
        timer(SessionTimer.DONE_CHIPS),
        w.ready,
        CancelTimer(SessionTimer.HANDOFF_LIMIT),
    ),
    row(
        "Done(handoff) + hand-off limit (2 min without a field long enough)",
        w.doneHandoff.copy(focus = Focus.None),
        timer(SessionTimer.HANDOFF_LIMIT),
        SessionState.Hidden,
        CancelTimer(SessionTimer.DONE_CHIPS),
    ),
    row("Done(typed) + hand-off limit: not its timer", w.doneTyped, timer(SessionTimer.HANDOFF_LIMIT), w.doneTyped),
    row(
        "Done(handoff) + press: next dictation, both timers stop",
        w.doneHandoff,
        SessionEvent.MicPressed,
        w.armingTap,
        CancelTimer(SessionTimer.DONE_CHIPS),
        CancelTimer(SessionTimer.HANDOFF_LIMIT),
        SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
    ),
    row(
        "Done + Inserted (Raw reply): Raw gone, Undo still restores the original",
        w.doneTyped,
        SessionEvent.Inserted(w.sessionId, w.receipt.copy(text = "hello world", replaced = "Hello world.")),
        w.doneTyped.copy(receipt = w.receipt.copy(text = "hello world", replaced = ""), rawAlternative = null),
        ScheduleTimer(SessionTimer.DONE_CHIPS),
    ),
    row(
        "Done(handoff, command) + Inserted (Paste reply): chip timer becomes Undo",
        w.doneHandoff.copy(session = w.commandSession),
        SessionEvent.Inserted(w.sessionId, w.receipt),
        SessionState.Done(w.commandSession, w.receipt, null, w.available),
        CancelTimer(SessionTimer.DONE_CHIPS),
        CancelTimer(SessionTimer.HANDOFF_LIMIT),
        ScheduleTimer(SessionTimer.COMMAND_UNDO),
    ),
    row(
        "Done + press: next dictation",
        w.doneTyped,
        SessionEvent.MicPressed,
        w.armingTap,
        CancelTimer(SessionTimer.DONE_CHIPS),
        SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
    ),
    row(
        "Done + press on a blocked focus explains",
        w.doneHandoff.copy(focus = w.blocked),
        SessionEvent.MicPressed,
        w.doneHandoff.copy(focus = w.blocked),
        SessionEffect.ShowChip(Chip.Explanation(UnavailableReason.SERVER_NOT_CONFIGURED)),
    ),
    row(
        "Done + correction offers learning",
        w.doneTyped,
        SessionEvent.CorrectionDetected(w.correction),
        w.doneTyped,
        SessionEffect.ShowChip(Chip.LearnOffer(w.correction)),
    ),
    row(
        "Done(handoff) + Paste with no field focused: nothing",
        w.doneHandoff.copy(focus = Focus.None),
        action(BubbleAction.PASTE),
        w.doneHandoff.copy(focus = Focus.None),
    ),
    row(
        "Done + press with no field focused: nothing",
        w.doneTyped.copy(focus = Focus.None),
        SessionEvent.MicPressed,
        w.doneTyped.copy(focus = Focus.None),
    ),
)

/** Rows of the table (problem states). */
val ProblemRows: List<Row> = listOf(
    row(
        "Problem + Retry with kept audio",
        w.problemKept,
        action(BubbleAction.RETRY),
        w.transcribing,
        SessionEffect.Transcribe(w.session, w.clip),
    ),
    row(
        "Problem + Retry without audio records again",
        w.problemNoSession,
        action(BubbleAction.RETRY),
        w.armingTap,
        SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
    ),
    row(
        "Problem + Fix",
        w.problemFix,
        action(BubbleAction.FIX),
        w.problemFix,
        SessionEffect.OpenFix(KeyRejected.fix),
    ),
    row(
        "Problem + Dismiss deletes kept audio",
        w.problemKept,
        action(BubbleAction.DISMISS),
        w.ready,
        SessionEffect.DeleteRecording(w.recording),
    ),
    row(
        "Problem(dismiss only) + Dismiss stops the timer",
        w.problemDismiss,
        action(BubbleAction.DISMISS),
        w.ready,
        CancelTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row("Problem(dismiss only) + timer", w.problemDismiss, timer(SessionTimer.PROBLEM_DISMISS), w.ready),
    row(
        "Problem + press records again and deletes kept audio",
        w.problemKept,
        SessionEvent.MicPressed,
        w.armingTap,
        SessionEffect.DeleteRecording(w.recording),
        SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
    ),
    row(
        "Problem(dismiss only) + press stops the timer",
        w.problemDismiss,
        SessionEvent.MicPressed,
        w.armingTap,
        CancelTimer(SessionTimer.PROBLEM_DISMISS),
        SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
    ),
    row(
        "Problem + queue failed: storage full gains Fix, timer stops",
        w.problemDismiss.copy(error = ServerUnreachable),
        SessionEvent.QueueFailed(w.sessionId, StorageFull),
        w.problemDismiss.copy(error = StorageFull),
        CancelTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row(
        "Problem(fix) + queue failed: storage failed is dismiss only, timer starts",
        w.problemFix,
        SessionEvent.QueueFailed(w.sessionId, StorageFailed),
        w.problemFix.copy(error = StorageFailed),
        ScheduleTimer(SessionTimer.PROBLEM_DISMISS),
    ),
    row("Problem + focus moves: recorded", w.problemFix, focus(Focus.None), w.problemFix.copy(focus = Focus.None)),
)

private fun focus(focus: Focus) = SessionEvent.FocusChanged(focus)

private fun action(action: BubbleAction) = SessionEvent.ActionTapped(action)

private fun timer(timer: SessionTimer) = SessionEvent.TimerFired(timer)

private fun started() =
    SessionEvent.RecordingStarted(SessionId("session-2"), RecordingId("recording-2"), Fixtures.Epoch)
