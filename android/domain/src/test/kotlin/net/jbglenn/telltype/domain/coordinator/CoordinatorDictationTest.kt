package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Trigger
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Whole dictations with real effects against the fakes: the success paths of the scenarios. */
class CoordinatorDictationTest {
    @Test
    fun `tap dictation runs every stage and leaves nothing behind`() = runTest {
        val w = CoordinatorWorld(this)
        val atInsert = mutableListOf<HistoryEntry>()
        w.inserter.insertCall.answers { (target, text) ->
            atInsert += w.history.entries
            Outcome.Ok(Fixtures.insertReceipt(target = target, text = text))
        }
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("So I think we should ship it on Friday.")))
        w.speech.transcribeCall.returns(Outcome.Ok(Fixtures.transcript(Spoken.LONG)))
        w.start()
        assertEquals(SessionState.Ready(w.field, BubbleMode.DICTATE), w.state)
        assertEquals(1, w.health.probeCall.calls.size)

        w.send(SessionEvent.MicPressed)
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        assertTrue(w.microphone.running)
        assertEquals(listOf(RecordingId("recording-1")), w.audio.startCall.calls)
        assertEquals(1, w.speech.prepareCount)
        assertEquals(1, w.contextReader.readCall.calls.size)
        w.advance(3.seconds)
        w.send(SessionEvent.FinishTapped)

        val done = w.state as SessionState.Done
        assertEquals("So I think we should ship it on Friday.", done.receipt.text)
        assertEquals(Spoken.LONG, done.rawAlternative)
        assertEquals(listOf(Spoken.LONG), atInsert.map { it.rawTranscript }, "transcript saved before insertion")
        val row = w.history.entries.single()
        assertEquals("So I think we should ship it on Friday.", row.finalText)
        assertEquals(Spoken.LONG, row.rawTranscript)
        assertEquals(CleanupStatus.APPLIED, row.cleanupStatus)
        assertEquals(InsertedVia.INPUT_CONNECTION, row.insertedVia)
        assertEquals(Fixtures.Epoch, row.createdAt)
        assertEquals(DictationMode.DICTATE, row.mode)
        assertEquals(3.seconds, row.timings.recording)
        assertEquals(null, row.errorCode)
        assertEquals(emptySet<RecordingId>(), w.recordings.recordings)
        assertFalse(w.microphone.running)
        assertEquals(
            listOf(FeedbackCue.RECORDING_STARTED, FeedbackCue.RECORDING_STOPPED, FeedbackCue.DONE),
            w.haptics.played,
        )
        val request = w.cleaner.cleanCall.calls.single()
        assertEquals(Tone.FORMAL, request.tone)
        assertEquals(AppCategory.OTHER, request.category)
        assertEquals("Message", request.fieldHint)

        w.advance(5.seconds)
        assertEquals(SessionState.Ready(w.field, BubbleMode.DICTATE), w.state)
        w.idle()
        w.assertNothingRunning()
        w.closeAndAssertStopped()
    }

    @Test
    fun `stage timings are measured on the monotonic clock`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.stopCall.delay = 100.milliseconds
        w.speech.transcribeCall.delay = 1.seconds
        w.cleaner.cleanCall.delay = 500.milliseconds
        w.inserter.insertCall.delay = 200.milliseconds
        w.speech.transcribeCall.returns(Outcome.Ok(Fixtures.transcript(Spoken.LONG)))
        w.start()
        w.send(SessionEvent.MicPressed)
        w.advance(4.seconds)
        w.send(SessionEvent.FinishTapped)
        w.advance(2.seconds)
        val timings = w.history.entries.single().timings
        assertEquals(4.seconds, timings.recording)
        assertEquals(100.milliseconds, timings.finalising)
        assertEquals(1.seconds, timings.transcription)
        assertEquals(500.milliseconds, timings.cleanup)
        assertEquals(200.milliseconds, timings.insertion)
        assertEquals(1800.milliseconds, timings.endToEnd)
        w.closeAndAssertStopped()
    }

    @Test
    fun `hold dictation finishes on release, a tap release does not`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.HoldDetected)
        assertEquals(Trigger.HOLD, (w.state as SessionState.Listening).session.trigger)
        w.send(SessionEvent.MicReleased)
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.advance(5.seconds)
        w.send(SessionEvent.MicPressed, SessionEvent.MicReleased)
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a whole-utterance snippet is inserted without the model`() = runTest {
        val w = CoordinatorWorld(this)
        w.snippets.add("my address", "1 Main Street, Springfield")
        w.start()
        w.dictate("My address.")
        assertEquals("1 Main Street, Springfield", (w.state as SessionState.Done).receipt.text)
        assertEquals(null, (w.state as SessionState.Done).rawAlternative)
        assertTrue(w.cleaner.cleanCall.calls.isEmpty())
        assertEquals(DictationMode.SNIPPET, w.history.entries.single().mode)
        w.idle()
        assertEquals(1, w.snippets.snippets.first().single().uses)
        w.closeAndAssertStopped()
    }

    @Test
    fun `an embedded snippet goes to the model as a marker and comes back as its text`() = runTest {
        val w = CoordinatorWorld(this)
        w.snippets.add("sign off", "Best regards, Jo")
        w.cleaner.cleanCall.answers { request ->
            Outcome.Ok(Fixtures.cleanedText(request.transcript.replaceFirstChar { it.uppercase() } + "."))
        }
        w.start()
        w.dictate("please add my sign off at the end of the email")
        val sent = w.cleaner.cleanCall.calls.single().transcript
        assertFalse("sign off" in sent)
        assertTrue("⟦S1⟧" in sent)
        assertEquals(
            "Please add my Best regards, Jo at the end of the email.",
            (w.state as SessionState.Done).receipt.text,
        )
        w.closeAndAssertStopped()
    }

    @Test
    fun `short phrases and cleanup switched off skip the model`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.dictate("hello world")
        assertEquals("Hello world.", (w.state as SessionState.Done).receipt.text)
        assertEquals(CleanupStatus.SKIPPED_SHORT, w.history.entries.single().cleanupStatus)
        w.advance(5.seconds)
        w.settingsRepository.update { it.copy(cleanup = it.cleanup.copy(enabled = false)) }
        w.runCurrent()
        w.dictate(Spoken.LONG)
        assertEquals(Spoken.LONG_BY_RULES, (w.state as SessionState.Done).receipt.text)
        assertEquals(CleanupStatus.OFF, w.history.entries.last().cleanupStatus)
        assertTrue(w.cleaner.cleanCall.calls.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `replacements, protected terms and the text before the cursor shape the result`() = runTest {
        val w = CoordinatorWorld(this)
        w.dictionary.addTerm("Kubernetes", emptyList(), EntrySource.MANUAL)
        w.dictionary.addReplacement("cooper", "Kubernetes", EntrySource.MANUAL)
        w.contextReader.readCall.returns(FieldContext("Deploy it on", "", ""))
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("Cooper cluster today.")))
        w.start()
        w.dictate("deploy it on the cooper cluster today please")
        assertEquals(" Kubernetes cluster today.", (w.state as SessionState.Done).receipt.text)
        val request = w.cleaner.cleanCall.calls.single()
        assertEquals("Deploy it on", request.contextBefore)
        assertEquals(listOf("Kubernetes"), request.dictionary)
        assertEquals(listOf("Kubernetes"), w.speech.transcribeCall.calls.single().second.terms)
        w.idle()
        assertEquals(1, w.dictionary.replacements.first().single().uses)
        assertEquals(1, w.dictionary.terms.first().single().uses)
        w.closeAndAssertStopped()
    }

    @Test
    fun `command mode rewrites the selection and Undo restores it`() = runTest {
        val w = CoordinatorWorld(this)
        w.contextReader.readCall.returns(FieldContext("", "", "teh selection"))
        w.cleaner.commandCall.returns(Outcome.Ok(Fixtures.cleanedText("The selection.")))
        w.start(focus = false)
        w.focus(selection = TextRange(0, 13))
        assertEquals(SessionState.Ready(w.field, BubbleMode.COMMAND), w.state)
        w.dictate("fix the typo")
        val request = w.cleaner.commandCall.calls.single()
        assertEquals("teh selection", request.selection)
        assertEquals("fix the typo", request.instruction)
        val done = w.state as SessionState.Done
        assertEquals("The selection.", done.receipt.text)
        assertEquals(DictationMode.COMMAND, w.history.entries.single().mode)
        assertTrue(w.editWatcher.watches.isEmpty(), "Command Mode does not learn")
        w.advance(9.seconds)
        assertInstanceOf(SessionState.Done::class.java, w.state, "Undo is offered for 10 s")
        w.send(SessionEvent.ActionTapped(BubbleAction.UNDO))
        assertEquals(done.receipt to done.receipt.replaced, w.inserter.replaceCall.calls.single())
        assertEquals(SessionState.Ready(w.field, BubbleMode.COMMAND), w.state)
        w.idle()
        w.assertNothingRunning()
        w.closeAndAssertStopped()
    }

    @Test
    fun `Raw swaps in the transcript, then Undo removes the dictation`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("Ship it Friday.")))
        w.start()
        w.dictate()
        w.advance(4.seconds)
        w.send(SessionEvent.ActionTapped(BubbleAction.RAW))
        val afterRaw = w.state as SessionState.Done
        assertEquals(Spoken.LONG, afterRaw.receipt.text)
        assertEquals(null, afterRaw.rawAlternative)
        w.advance(4.seconds)
        assertInstanceOf(SessionState.Done::class.java, w.state, "Raw restarted the chip timer")
        w.send(SessionEvent.ActionTapped(BubbleAction.UNDO))
        assertEquals(listOf(Spoken.LONG, ""), w.inserter.replaceCall.calls.map { it.second })
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `an incognito field gets no context read, no history and no learning`() = runTest {
        val w = CoordinatorWorld(this, field = Fixtures.fieldTarget(incognito = true))
        w.dictionary.addReplacement("friday", "Friday", EntrySource.MANUAL)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("We ship on friday.")))
        w.start()
        w.dictate()
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertTrue(w.contextReader.readCall.calls.isEmpty())
        val request = w.cleaner.cleanCall.calls.single()
        assertEquals("", request.contextBefore)
        assertEquals(null, request.fieldHint)
        w.idle()
        assertTrue(w.history.entries.isEmpty())
        assertTrue(w.editWatcher.watches.isEmpty())
        assertEquals(0, w.dictionary.replacements.first().single().uses)
        w.closeAndAssertStopped()
    }

    @Test
    fun `history switched off writes no row`() = runTest {
        val w = CoordinatorWorld(
            this,
            settings = configuredSettings().let {
                it.copy(privacy = it.privacy.copy(historyRetention = Duration.ZERO))
            },
        )
        w.start()
        w.dictate()
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertTrue(w.history.entries.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `the Save history switch off writes no row, with the retention unchanged`() = runTest {
        val w = CoordinatorWorld(
            this,
            settings = configuredSettings().let { it.copy(privacy = it.privacy.copy(saveHistory = false)) },
        )
        w.start()
        w.dictate()
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertTrue(w.history.entries.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `the session limit warns a minute before and then finishes by itself`() = runTest {
        val w = CoordinatorWorld(
            this,
            settings = configuredSettings().let { it.copy(dictation = it.dictation.copy(sessionLimit = 2.minutes)) },
        )
        w.start()
        w.send(SessionEvent.MicPressed)
        w.advance(1.minutes)
        assertEquals(listOf<Chip>(Chip.SessionEndingSoon), w.chips)
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        w.advance(1.minutes)
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertEquals(1, w.audio.stopCall.calls.size)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a call interrupts the recording and the text still arrives`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.send(SessionEvent.MicPressed)
        w.audio.interrupt(Interruption.CALL_STARTED)
        w.runCurrent()
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `focus moving away in every stage does not stop the session`() = runTest {
        val w = CoordinatorWorld(this)
        val other = Fixtures.fieldTarget(handle = "field-2", app = "org.example.mail")
        w.speech.transcribeCall.returns(Outcome.Ok(Fixtures.transcript(Spoken.LONG)))
        w.speech.transcribeCall.delay = 1.seconds
        w.cleaner.cleanCall.delay = 1.seconds
        w.start()
        w.send(SessionEvent.MicPressed)
        w.focus(other)
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        w.send(SessionEvent.FinishTapped)
        w.focus(w.field)
        w.advance(1.seconds)
        w.focus(other)
        assertInstanceOf(SessionState.Working::class.java, w.state)
        w.advance(1.seconds)
        assertEquals(w.field, w.inserter.insertCall.calls.single().first, "typed only into the original field")
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.advance(5.seconds)
        assertEquals(SessionState.Ready(other, BubbleMode.DICTATE), w.state)
        w.closeAndAssertStopped()
    }
}
