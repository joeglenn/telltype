package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.error.CleanupRejected
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.SelectionUnreadable
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Runs events through [reduce] and records every effect, like the coordinator does. */
class SessionDriver(initial: SessionState) {
    /** The current state. */
    var state: SessionState = initial
        private set

    /** Every effect so far, in order. */
    val effects: MutableList<SessionEffect> = mutableListOf()

    /** Feeds [events] in order; returns the effects of these events only. */
    fun send(vararg events: SessionEvent): List<SessionEffect> {
        val before = effects.size
        events.forEach { event ->
            val transition = reduce(state, event)
            state = transition.state
            effects += transition.effects
        }
        return effects.drop(before)
    }

    /** Timers scheduled and not cancelled or fired since. */
    fun runningTimers(fired: Set<SessionTimer> = emptySet()): Set<SessionTimer> {
        val running = mutableSetOf<SessionTimer>()
        effects.forEach { effect ->
            when (effect) {
                is SessionEffect.ScheduleTimer -> running += effect.timer
                is SessionEffect.CancelTimer -> running -= effect.timer
                else -> Unit
            }
        }
        return running - fired
    }
}

/** Whole dictations as event sequences (brief A2b, scenario list). */
class ReducerScenarioTest {
    private val w = ReducerWorld()
    private val id = w.sessionId
    private val started = SessionEvent.RecordingStarted(id, w.recording, Fixtures.Epoch)

    private fun ready() = SessionDriver(w.ready)

    /** Press, audio flows, ✓, audio finalised, transcribed: the session is cleaning. */
    private fun SessionDriver.untilCleaning(): SessionDriver = apply {
        send(SessionEvent.MicPressed, started, SessionEvent.FinishTapped)
        send(SessionEvent.AudioFinalised(id, w.clip), SessionEvent.Transcribed(id, w.transcript))
    }

    @Test
    fun `tap happy path`() {
        val driver = ready().untilCleaning()
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.Inserted(id, w.receipt))
        assertEquals(w.doneTyped, driver.state)
        driver.send(SessionEvent.TimerFired(SessionTimer.DONE_CHIPS))
        assertEquals(w.ready, driver.state)
        assertEquals(
            listOf(
                SessionEffect.StartRecording(w.field, BubbleMode.DICTATE),
                SessionEffect.Haptic(FeedbackCue.RECORDING_STARTED),
                SessionEffect.ScheduleTimer(SessionTimer.SESSION_WARNING),
                SessionEffect.ScheduleTimer(SessionTimer.SESSION_LIMIT),
                SessionEffect.CancelTimer(SessionTimer.SESSION_WARNING),
                SessionEffect.CancelTimer(SessionTimer.SESSION_LIMIT),
                SessionEffect.Haptic(FeedbackCue.RECORDING_STOPPED),
                SessionEffect.StopRecording(id, w.recording),
                SessionEffect.Transcribe(w.session, w.clip),
                SessionEffect.SaveHistory(w.session, w.transcript, null, InsertedVia.NONE, null),
                SessionEffect.Clean(w.session, w.transcript),
                SessionEffect.Insert(id, w.field, w.cleaned.text),
                SessionEffect.DeleteRecording(w.recording),
                SessionEffect.Haptic(FeedbackCue.DONE),
                SessionEffect.SaveHistory(w.session, w.transcript, w.cleaned, InsertedVia.INPUT_CONNECTION, null),
                SessionEffect.ScheduleTimer(SessionTimer.DONE_CHIPS),
                SessionEffect.WatchFieldEdits(w.receipt),
            ),
            driver.effects,
        )
        assertEquals(emptySet<SessionTimer>(), driver.runningTimers(fired = setOf(SessionTimer.DONE_CHIPS)))
    }

    @Test
    fun `hold happy path`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, SessionEvent.HoldDetected, started)
        assertEquals(w.listeningHold, driver.state)
        val stop = driver.send(SessionEvent.MicReleased)
        assertTrue(SessionEffect.StopRecording(id, w.recording) in stop)
        driver.send(SessionEvent.AudioFinalised(id, w.clip), SessionEvent.Transcribed(id, w.transcript))
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.Inserted(id, w.receipt))
        assertTrue(driver.state is SessionState.Done)
    }

    @Test
    fun `hold detected after the audio started still finishes on release`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, started, SessionEvent.HoldDetected, SessionEvent.MicReleased)
        assertEquals(Stage.Finalising, (driver.state as SessionState.Working).stage)
    }

    @Test
    fun `tap release is not a finish`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, SessionEvent.MicReleased, started, SessionEvent.MicReleased)
        assertEquals(w.listeningTap, driver.state)
    }

    @Test
    fun `cancel while listening leaves nothing running and no recording`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, started, SessionEvent.CancelTapped)
        assertEquals(w.ready, driver.state)
        assertTrue(SessionEffect.CancelRecording(w.recording) in driver.effects)
        assertEquals(emptySet<SessionTimer>(), driver.runningTimers())
    }

    @Test
    fun `cancel while arming, then the late start is cancelled`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, SessionEvent.CancelTapped)
        val late = driver.send(started)
        assertEquals(listOf<SessionEffect>(SessionEffect.CancelRecording(w.recording)), late)
        assertEquals(w.ready, driver.state)
    }

    @Test
    fun `cancel while working ignores the late results`() {
        val driver = ready()
        driver.send(
            SessionEvent.MicPressed,
            started,
            SessionEvent.FinishTapped,
            SessionEvent.AudioFinalised(id, w.clip),
        )
        val cancel = driver.send(SessionEvent.CancelTapped)
        assertEquals(listOf(SessionEffect.CancelWork(id), SessionEffect.DeleteRecording(w.recording)), cancel)
        val late = driver.send(SessionEvent.Transcribed(id, w.transcript), SessionEvent.Cleaned(id, w.cleaned))
        assertEquals(emptyList<SessionEffect>(), late)
        assertEquals(w.ready, driver.state)
    }

    @Test
    fun `no speech detected by the silence check`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, started, SessionEvent.FinishTapped, SessionEvent.AudioFailed(id, NoSpeech))
        assertEquals(w.problemDismiss, driver.state)
        assertTrue(SessionEffect.DeleteRecording(w.recording) in driver.effects)
        driver.send(SessionEvent.TimerFired(SessionTimer.PROBLEM_DISMISS))
        assertEquals(w.ready, driver.state)
    }

    @Test
    fun `empty transcript keeps the audio, Retry transcribes it again`() {
        val driver = ready()
        driver.send(
            SessionEvent.MicPressed,
            started,
            SessionEvent.FinishTapped,
            SessionEvent.AudioFinalised(id, w.clip),
        )
        driver.send(SessionEvent.TranscriptionFailed(id, EmptyTranscript))
        assertEquals(w.problemKept, driver.state)
        assertTrue(driver.effects.none { it is SessionEffect.DeleteRecording })
        val retry = driver.send(SessionEvent.ActionTapped(BubbleAction.RETRY))
        assertEquals(listOf<SessionEffect>(SessionEffect.Transcribe(w.session, w.clip)), retry)
        driver.send(SessionEvent.Transcribed(id, w.transcript), SessionEvent.Cleaned(id, w.cleaned))
        driver.send(SessionEvent.Inserted(id, w.receipt))
        assertTrue(driver.state is SessionState.Done)
    }

    @Test
    fun `server unreachable queues the recording`() {
        val driver = ready()
        driver.send(
            SessionEvent.MicPressed,
            started,
            SessionEvent.FinishTapped,
            SessionEvent.AudioFinalised(id, w.clip),
        )
        driver.send(SessionEvent.TranscriptionFailed(id, ServerUnreachable))
        assertTrue(SessionEffect.EnqueuePendingJob(w.session, w.clip) in driver.effects)
        assertTrue(driver.effects.none { it is SessionEffect.DeleteRecording })
        assertEquals(SessionState.Problem(ServerUnreachable, w.session, null, w.available), driver.state)
    }

    @Test
    fun `key rejected queues the audio and offers Fix`() {
        val driver = ready()
        driver.send(
            SessionEvent.MicPressed,
            started,
            SessionEvent.FinishTapped,
            SessionEvent.AudioFinalised(id, w.clip),
        )
        driver.send(SessionEvent.TranscriptionFailed(id, KeyRejected))
        assertTrue(SessionEffect.EnqueuePendingJob(w.session, w.clip) in driver.effects)
        assertEquals(setOf(BubbleAction.FIX, BubbleAction.DISMISS), (driver.state as SessionState.Problem).actions)
        assertEquals(
            listOf<SessionEffect>(SessionEffect.OpenFix(KeyRejected.fix)),
            driver.send(SessionEvent.ActionTapped(BubbleAction.FIX)),
        )
    }

    @Test
    fun `503 is queued, the next dictation succeeds`() {
        val driver = ready()
        driver.send(
            SessionEvent.MicPressed,
            started,
            SessionEvent.FinishTapped,
            SessionEvent.AudioFinalised(id, w.clip),
        )
        driver.send(SessionEvent.TranscriptionFailed(id, ServerWarmingUp))
        assertTrue(SessionEffect.EnqueuePendingJob(w.session, w.clip) in driver.effects)
        val second = SessionId("session-2")
        val secondRecording = RecordingId("recording-2")
        val press = driver.send(SessionEvent.MicPressed)
        assertTrue(SessionEffect.CancelTimer(SessionTimer.PROBLEM_DISMISS) in press)
        driver.send(SessionEvent.RecordingStarted(second, secondRecording, Fixtures.Epoch), SessionEvent.FinishTapped)
        driver.send(SessionEvent.AudioFinalised(second, w.clip.copy(recording = secondRecording)))
        driver.send(SessionEvent.Transcribed(second, w.transcript), SessionEvent.Cleaned(second, w.cleaned))
        driver.send(SessionEvent.Inserted(second, w.receipt))
        assertEquals(second, (driver.state as SessionState.Done).session.id)
    }

    @Test
    fun `cleanup timeout falls back to rules`() {
        val driver = ready().untilCleaning()
        val fallback = driver.send(SessionEvent.CleanupFailed(id, CleanupTimedOut))
        assertEquals(
            listOf<SessionEffect>(SessionEffect.CleanWithRules(w.session, w.transcript, CleanupStatus.SKIPPED_TIMEOUT)),
            fallback,
        )
        val rules = Fixtures.cleanedText(text = "Hello world.", status = CleanupStatus.SKIPPED_TIMEOUT)
        driver.send(SessionEvent.Cleaned(id, rules), SessionEvent.Inserted(id, w.receipt))
        assertTrue(driver.state is SessionState.Done)
    }

    @Test
    fun `guard rejection falls back to rules`() {
        val driver = ready().untilCleaning()
        val fallback = driver.send(SessionEvent.CleanupFailed(id, CleanupRejected(GuardVerdict.EmptyReply)))
        assertEquals(
            listOf<SessionEffect>(SessionEffect.CleanWithRules(w.session, w.transcript, CleanupStatus.SKIPPED_GUARD)),
            fallback,
        )
    }

    @Test
    fun `field gone hands the text off with Paste`() {
        val driver = ready().untilCleaning()
        driver.send(SessionEvent.Cleaned(id, w.cleaned))
        val handoff = driver.send(SessionEvent.InsertionFailed(id, FieldGone))
        assertEquals(listOf<SessionEffect>(SessionEffect.HandOff(w.session, w.cleaned.text)), handoff)
        driver.send(SessionEvent.HandedOff(id, w.handoffReceipt), SessionEvent.FocusChanged(w.otherFocus))
        assertEquals(setOf(BubbleAction.PASTE), (driver.state as SessionState.Done).actions)
        val paste = driver.send(SessionEvent.ActionTapped(BubbleAction.PASTE))
        assertEquals(listOf<SessionEffect>(SessionEffect.Insert(id, w.otherField, w.handoffReceipt.text)), paste)
        driver.send(SessionEvent.Inserted(id, w.receipt.copy(target = w.otherField)))
        assertEquals(setOf(BubbleAction.UNDO), (driver.state as SessionState.Done).actions)
    }

    @Test
    fun `after a hand-off with no field the Paste chip waits until a field has had the focus for the chip time`() {
        val driver = ready().untilCleaning()
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.FocusChanged(Focus.None))
        driver.send(SessionEvent.InsertionFailed(id, FieldGone), SessionEvent.HandedOff(id, w.handoffReceipt))
        assertEquals(setOf(SessionTimer.HANDOFF_LIMIT), driver.runningTimers())
        driver.send(SessionEvent.FocusChanged(w.otherFocus))
        assertEquals(setOf(SessionTimer.HANDOFF_LIMIT, SessionTimer.DONE_CHIPS), driver.runningTimers())
        driver.send(SessionEvent.FocusChanged(Focus.None))
        assertEquals(setOf(SessionTimer.HANDOFF_LIMIT), driver.runningTimers(), "a field that left early")
        assertEquals(setOf(BubbleAction.PASTE), (driver.state as SessionState.Done).actions)
        driver.send(SessionEvent.FocusChanged(w.otherFocus), SessionEvent.TimerFired(SessionTimer.DONE_CHIPS))
        assertEquals(SessionState.Ready(w.otherField, BubbleMode.DICTATE), driver.state)
        assertEquals(emptySet<SessionTimer>(), driver.runningTimers(fired = setOf(SessionTimer.DONE_CHIPS)))
    }

    @Test
    fun `a hand-off chip that never met a field ends after the hand-off limit`() {
        val driver = ready().untilCleaning()
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.FocusChanged(Focus.None))
        driver.send(SessionEvent.InsertionFailed(id, FieldGone), SessionEvent.HandedOff(id, w.handoffReceipt))
        driver.send(SessionEvent.TimerFired(SessionTimer.HANDOFF_LIMIT))
        assertEquals(SessionState.Hidden, driver.state)
        assertEquals(emptySet<SessionTimer>(), driver.runningTimers(fired = setOf(SessionTimer.HANDOFF_LIMIT)))
    }

    @Test
    fun `never use the clipboard gives Copy instead of Paste`() {
        val driver = ready().untilCleaning()
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.InsertionFailed(id, FieldChanged))
        driver.send(SessionEvent.HandedOff(id, w.noneReceipt))
        assertEquals(setOf(BubbleAction.COPY), (driver.state as SessionState.Done).actions)
        assertTrue(
            SessionEffect.SaveHistory(w.session, w.transcript, w.cleaned, InsertedVia.NONE, null) in driver.effects,
        )
        val copy = driver.send(SessionEvent.ActionTapped(BubbleAction.COPY))
        assertEquals(listOf<SessionEffect>(SessionEffect.CopyToClipboard(w.noneReceipt.text)), copy)
    }

    @Test
    fun `session limit ends the recording after the warning`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, started)
        val warning = driver.send(SessionEvent.TimerFired(SessionTimer.SESSION_WARNING))
        assertEquals(listOf<SessionEffect>(SessionEffect.ShowChip(Chip.SessionEndingSoon)), warning)
        driver.send(SessionEvent.TimerFired(SessionTimer.SESSION_LIMIT))
        assertEquals(Stage.Finalising, (driver.state as SessionState.Working).stage)
        assertEquals(emptySet<SessionTimer>(), driver.runningTimers())
    }

    @Test
    fun `a call interrupts the recording and the text still arrives`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, started, SessionEvent.Interrupted(Interruption.CALL_STARTED))
        assertTrue(SessionEffect.StopRecording(id, w.recording) in driver.effects)
        driver.send(SessionEvent.AudioFinalised(id, w.clip), SessionEvent.Transcribed(id, w.transcript))
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.Inserted(id, w.receipt))
        assertTrue(driver.state is SessionState.Done)
    }

    @Test
    fun `command mode success with Undo`() {
        val driver = SessionDriver(SessionState.Ready(w.field, BubbleMode.COMMAND))
        driver.send(SessionEvent.MicPressed, started, SessionEvent.FinishTapped)
        driver.send(SessionEvent.AudioFinalised(id, w.clip), SessionEvent.Transcribed(id, w.transcript))
        val rewrite = w.receipt.copy(replaced = "old text")
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.Inserted(id, rewrite))
        val done = driver.state as SessionState.Done
        assertEquals(setOf(BubbleAction.UNDO), done.actions)
        assertTrue(driver.effects.none { it is SessionEffect.WatchFieldEdits })
        assertTrue(SessionEffect.ScheduleTimer(SessionTimer.COMMAND_UNDO) in driver.effects)
        val undo = driver.send(SessionEvent.ActionTapped(BubbleAction.UNDO))
        assertTrue(SessionEffect.ReplaceInserted(id, rewrite, "old text") in undo)
    }

    @Test
    fun `command mode failure ends in a problem without fallback`() {
        val driver = SessionDriver(SessionState.Ready(w.field, BubbleMode.COMMAND))
        driver.send(SessionEvent.MicPressed, started, SessionEvent.FinishTapped)
        driver.send(SessionEvent.AudioFinalised(id, w.clip), SessionEvent.Transcribed(id, w.transcript))
        driver.send(SessionEvent.CleanupFailed(id, SelectionUnreadable))
        assertEquals(SelectionUnreadable, (driver.state as SessionState.Problem).error)
        assertTrue(driver.effects.none { it is SessionEffect.CleanWithRules })
        assertTrue(SessionEffect.DeleteRecording(w.recording) in driver.effects)
    }

    @Test
    fun `Raw then Undo`() {
        val driver = ready().untilCleaning()
        driver.send(SessionEvent.Cleaned(id, w.cleaned), SessionEvent.Inserted(id, w.receipt))
        val raw = driver.send(SessionEvent.ActionTapped(BubbleAction.RAW))
        assertEquals(listOf<SessionEffect>(SessionEffect.ReplaceInserted(id, w.receipt, w.transcript.text)), raw)
        val rawReceipt = w.receipt.copy(text = w.transcript.text, replaced = w.receipt.text)
        driver.send(SessionEvent.Inserted(id, rawReceipt))
        val afterRaw = driver.state as SessionState.Done
        assertEquals(setOf(BubbleAction.UNDO), afterRaw.actions)
        val undo = driver.send(SessionEvent.ActionTapped(BubbleAction.UNDO))
        assertEquals(SessionEffect.ReplaceInserted(id, rawReceipt.copy(replaced = ""), ""), undo.last())
        assertEquals(w.ready, driver.state)
        assertEquals(emptyList<SessionEffect>(), driver.send(SessionEvent.Inserted(id, w.receipt)))
    }

    @Test
    fun `focus loss in every stage does not stop the session`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, SessionEvent.FocusChanged(Focus.None), started)
        driver.send(
            SessionEvent.FocusChanged(w.otherFocus),
            SessionEvent.FinishTapped,
            SessionEvent.FocusChanged(Focus.None),
        )
        driver.send(SessionEvent.AudioFinalised(id, w.clip), SessionEvent.FocusChanged(w.blocked))
        driver.send(SessionEvent.Transcribed(id, w.transcript), SessionEvent.FocusChanged(Focus.None))
        val insert = driver.send(SessionEvent.Cleaned(id, w.cleaned))
        assertEquals(listOf<SessionEffect>(SessionEffect.Insert(id, w.field, w.cleaned.text)), insert)
        driver.send(SessionEvent.FocusChanged(w.otherFocus), SessionEvent.InsertionFailed(id, FieldChanged))
        driver.send(SessionEvent.HandedOff(id, w.handoffReceipt))
        assertEquals(w.doneHandoff.copy(focus = w.otherFocus), driver.state)
        driver.send(SessionEvent.TimerFired(SessionTimer.DONE_CHIPS))
        assertEquals(SessionState.Ready(w.otherField, BubbleMode.DICTATE), driver.state)
    }

    @Test
    fun `a problem while the field is gone closes to Hidden`() {
        val driver = ready()
        driver.send(SessionEvent.MicPressed, started, SessionEvent.FocusChanged(Focus.None), SessionEvent.FinishTapped)
        driver.send(SessionEvent.AudioFailed(id, NoSpeech), SessionEvent.TimerFired(SessionTimer.PROBLEM_DISMISS))
        assertEquals(SessionState.Hidden, driver.state)
    }

    @Test
    fun `incognito dictation saves no history`() {
        val incognito = ReducerWorld(Fixtures.fieldTarget(incognito = true))
        val driver = SessionDriver(incognito.ready)
        driver.send(SessionEvent.MicPressed, SessionEvent.RecordingStarted(id, incognito.recording, Fixtures.Epoch))
        driver.send(SessionEvent.FinishTapped, SessionEvent.AudioFinalised(id, incognito.clip))
        driver.send(SessionEvent.Transcribed(id, incognito.transcript), SessionEvent.Cleaned(id, incognito.cleaned))
        driver.send(SessionEvent.Inserted(id, incognito.receipt))
        assertTrue(driver.state is SessionState.Done)
        assertTrue(driver.effects.none { it is SessionEffect.SaveHistory })
    }
}
