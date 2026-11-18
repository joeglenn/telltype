package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.testing.Fixtures

/**
 * Representative instances of every state and every event for the reducer tests, built from the `:testing`
 * fixtures. All in-session states belong to session `session-1` with recording `recording-1` on `field-1`.
 */
class ReducerWorld(val field: FieldTarget = Fixtures.fieldTarget()) {
    val otherField: FieldTarget = Fixtures.fieldTarget(handle = "field-2", app = "org.example.mail")
    val available: Focus.Available = Focus.Available(field, BubbleMode.DICTATE)
    val otherFocus: Focus.Available = Focus.Available(otherField, BubbleMode.DICTATE)
    val blocked: Focus.Blocked = Focus.Blocked(UnavailableReason.SERVER_NOT_CONFIGURED)
    val session = Fixtures.session(field = field)
    val commandSession = Fixtures.session(field = field, mode = BubbleMode.COMMAND)
    val sessionId: SessionId = session.id
    val recording = RecordingId("recording-1")
    val clip = Fixtures.audioClip()
    val faintClip = Fixtures.audioClip(speech = SpeechPresence.FAINT)
    val transcript = Fixtures.transcript()
    val cleaned = Fixtures.cleanedText()
    val receipt = Fixtures.insertReceipt(target = field)
    val handoffReceipt = Fixtures.insertReceipt(target = field, via = InsertedVia.HANDOFF)
    val noneReceipt = Fixtures.insertReceipt(target = field, via = InsertedVia.NONE)
    val correction = Correction("cooper", "Kubernetes")

    val ready = SessionState.Ready(field, BubbleMode.DICTATE)
    val unavailable = SessionState.Unavailable(UnavailableReason.SERVER_NOT_CONFIGURED)
    val armingTap = SessionState.Arming(field, BubbleMode.DICTATE, Trigger.TAP, available)
    val armingHold = armingTap.copy(trigger = Trigger.HOLD)
    val listeningTap = SessionState.Listening(session, recording, Fixtures.Epoch, available)
    val listeningHold = listeningTap.copy(session = session.copy(trigger = Trigger.HOLD))
    val finalising = working(Stage.Finalising)
    val transcribing = working(Stage.Transcribing(clip))
    val cleaning = working(Stage.Cleaning(transcript))
    val inserting = working(Stage.Inserting(transcript, cleaned))
    val doneTyped = SessionState.Done(session, receipt, transcript.text, available)
    val doneHandoff = SessionState.Done(session, handoffReceipt, null, available)
    val doneNone = SessionState.Done(session, noneReceipt, null, available)
    val doneCommand = SessionState.Done(commandSession, receipt.copy(replaced = "old text"), null, available)
    val problemDismiss = SessionState.Problem(NoSpeech, session, null, available)
    val problemKept = SessionState.Problem(EmptyTranscript, session, clip, available)
    val problemFix = SessionState.Problem(KeyRejected, session, null, available)
    val problemNoSession = SessionState.Problem(RecorderFailed, null, null, available)

    /** Every representative state, by label. */
    val states: Map<String, SessionState> = linkedMapOf(
        "Hidden" to SessionState.Hidden,
        "Ready" to ready,
        "Unavailable" to unavailable,
        "Arming.tap" to armingTap,
        "Arming.hold" to armingHold,
        "Listening.tap" to listeningTap,
        "Listening.hold" to listeningHold,
        "Working.finalising" to finalising,
        "Working.transcribing" to transcribing,
        "Working.cleaning" to cleaning,
        "Working.inserting" to inserting,
        "Done.typed" to doneTyped,
        "Done.handoff" to doneHandoff,
        "Done.none" to doneNone,
        "Done.command" to doneCommand,
        "Problem.dismissOnly" to problemDismiss,
        "Problem.keptAudio" to problemKept,
        "Problem.fix" to problemFix,
        "Problem.noSession" to problemNoSession,
    )

    private val other = SessionId("session-other")

    /** Every representative event, by label; stage results for the current and for another session. */
    val events: Map<String, SessionEvent> = linkedMapOf<String, SessionEvent>(
        "Focus.none" to SessionEvent.FocusChanged(Focus.None),
        "Focus.same" to SessionEvent.FocusChanged(available),
        "Focus.other" to SessionEvent.FocusChanged(otherFocus),
        "Focus.blocked" to SessionEvent.FocusChanged(blocked),
        "MicPressed" to SessionEvent.MicPressed,
        "HoldDetected" to SessionEvent.HoldDetected,
        "MicReleased" to SessionEvent.MicReleased,
        "DragStarted" to SessionEvent.DragStarted,
        "FinishTapped" to SessionEvent.FinishTapped,
        "CancelTapped" to SessionEvent.CancelTapped,
        "RecordingStarted.new" to
            SessionEvent.RecordingStarted(SessionId("session-2"), RecordingId("recording-2"), Fixtures.Epoch),
        "RecordingStarted.own" to SessionEvent.RecordingStarted(sessionId, recording, Fixtures.Epoch),
        "RecordingFailed" to SessionEvent.RecordingFailed(RecorderFailed),
        "Interrupted" to SessionEvent.Interrupted(Interruption.CALL_STARTED),
        "CorrectionDetected" to SessionEvent.CorrectionDetected(correction),
        "LearnAccepted" to SessionEvent.LearnAccepted(correction),
    ).apply {
        BubbleAction.entries.forEach { put("Action.$it", SessionEvent.ActionTapped(it)) }
        SessionTimer.entries.forEach { put("Timer.$it", SessionEvent.TimerFired(it)) }
        listOf("" to sessionId, ".stale" to other).forEach { (suffix, id) ->
            put("AudioFinalised$suffix", SessionEvent.AudioFinalised(id, clip))
            put("AudioFailed$suffix", SessionEvent.AudioFailed(id, StorageFailed))
            put("Transcribed$suffix", SessionEvent.Transcribed(id, transcript))
            put("TranscriptionFailed$suffix", SessionEvent.TranscriptionFailed(id, ServerUnreachable))
            put("Cleaned$suffix", SessionEvent.Cleaned(id, cleaned))
            put("CleanupFailed$suffix", SessionEvent.CleanupFailed(id, CleanupTimedOut))
            put("Inserted$suffix", SessionEvent.Inserted(id, receipt))
            put("InsertionFailed$suffix", SessionEvent.InsertionFailed(id, FieldGone))
            put("HandedOff$suffix", SessionEvent.HandedOff(id, handoffReceipt))
            put("QueueFailed$suffix", SessionEvent.QueueFailed(id, StorageFailed))
        }
    }

    private fun working(stage: Stage) = SessionState.Working(session, recording, stage, available)
}
