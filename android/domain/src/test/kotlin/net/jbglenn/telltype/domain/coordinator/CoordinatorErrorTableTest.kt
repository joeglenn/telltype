package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.CleanupRejected
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.SelectionUnreadable
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.error.SpeechEngineFailed
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Every row of the error table in docs/PLAN.md section 10, as whole dictations against the fakes. */
class CoordinatorErrorTableTest {
    private fun CoordinatorWorld.problem(): SessionState.Problem = state as SessionState.Problem

    @Test
    fun `microphone permission missing opens nothing, offers Fix and releases the service`() = runTest {
        val w = CoordinatorWorld(this)
        w.microphone.startCall.returns(Outcome.Err(MicrophonePermissionMissing))
        w.start()
        w.send(SessionEvent.MicPressed)
        assertEquals(MicrophonePermissionMissing, w.problem().error)
        assertEquals(null, w.problem().session)
        assertTrue(w.audio.startCall.calls.isEmpty())
        assertTrue(w.recordings.recordings.isEmpty())
        w.send(SessionEvent.ActionTapped(BubbleAction.FIX))
        assertEquals(listOf(FixTarget.MICROPHONE_PERMISSION), w.fixLauncher.openCall.calls)
        assertEquals(1, w.microphone.stopCount)
        w.idle()
        w.assertNothingRunning()
        w.closeAndAssertStopped()
    }

    @Test
    fun `microphone in use deletes the new file and the problem closes by itself`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.startCall.returns(Outcome.Err(MicrophoneInUse))
        w.start()
        w.send(SessionEvent.MicPressed)
        assertEquals(MicrophoneInUse, w.problem().error)
        assertTrue(w.recordings.recordings.isEmpty())
        assertFalse(w.microphone.running)
        w.advance(5.seconds)
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a recorder that does not start in time is a retryable failure`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.startCall.delay = 30.seconds
        w.start()
        w.send(SessionEvent.MicPressed)
        w.advance(5.seconds)
        assertEquals(RecorderFailed, w.problem().error)
        assertTrue(w.recordings.recordings.isEmpty())
        w.audio.startCall.delay = 0.seconds
        w.send(SessionEvent.ActionTapped(BubbleAction.RETRY))
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `storage full when the file is created offers the storage fix`() = runTest {
        val w = CoordinatorWorld(this)
        w.recordings.faults.failure = StorageFull
        w.start()
        w.send(SessionEvent.MicPressed)
        assertEquals(StorageFull, w.problem().error)
        assertTrue(w.audio.startCall.calls.isEmpty())
        w.send(SessionEvent.ActionTapped(BubbleAction.FIX))
        assertEquals(listOf(FixTarget.STORAGE), w.fixLauncher.openCall.calls)
        w.closeAndAssertStopped()
    }

    @Test
    fun `no speech detected uploads nothing and deletes the recording`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.stopCall.returns(Outcome.Err(NoSpeech))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(NoSpeech, w.problem().error)
        assertTrue(w.speech.transcribeCall.calls.isEmpty())
        w.idle()
        assertTrue(w.recordings.recordings.isEmpty())
        assertEquals("no_speech", w.history.entries.single().errorCode)
        w.closeAndAssertStopped()
    }

    @Test
    fun `text from a faint clip is dropped`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.stopCall.answers {
            Outcome.Ok(Fixtures.audioClip(recording = it?.value ?: "recording-1", speech = SpeechPresence.FAINT))
        }
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(NoSpeech, w.problem().error)
        assertEquals(1, w.speech.transcribeCall.calls.size)
        assertTrue(w.inserter.insertCall.calls.isEmpty())
        w.idle()
        assertTrue(w.recordings.recordings.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `an unreachable server queues the recording with the app's category and tone`() = runTest {
        val w = CoordinatorWorld(this, field = Fixtures.fieldTarget(app = "org.telegram.messenger"))
        w.speech.transcribeCall.returns(Outcome.Err(ServerUnreachable))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(ServerUnreachable, w.problem().error)
        assertEquals(null, w.problem().keptAudio)
        w.runCurrent()
        val job = w.pendingJobs.jobs.first().single()
        assertEquals(RecordingId("recording-1"), job.recording)
        assertEquals(AppCategory.PERSONAL_CHAT, job.category)
        assertEquals(Tone.CASUAL, job.tone)
        assertEquals(setOf(RecordingId("recording-1")), w.recordings.recordings, "the queue owns the file")
        assertEquals("server_unreachable", w.history.entries.single().errorCode)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a transcription that never answers counts as unreachable and is queued`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.delay = 10_000.seconds
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.advance(120.seconds)
        assertEquals(ServerUnreachable, w.problem().error)
        assertEquals(1, w.pendingJobs.jobs.first().size)
        w.closeAndAssertStopped()
    }

    @Test
    fun `an engine that throws counts as an engine failure and is queued`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.answers { error("engine exploded with secret words") }
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(SpeechEngineFailed, w.problem().error)
        assertEquals(1, w.pendingJobs.jobs.first().size)
        assertFalse("secret words" in w.logText)
        assertTrue("transcription threw IllegalStateException" in w.logText)
        w.closeAndAssertStopped()
    }

    @Test
    fun `key rejected queues the audio and Fix opens server setup`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.returns(Outcome.Err(KeyRejected))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(KeyRejected, w.problem().error)
        w.send(SessionEvent.ActionTapped(BubbleAction.FIX))
        assertEquals(listOf(FixTarget.SERVER_SETUP), w.fixLauncher.openCall.calls)
        assertEquals(1, w.pendingJobs.jobs.first().size)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a busy server queues the audio and the next dictation succeeds`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.returnsNext(Outcome.Err(ServerWarmingUp))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(ServerWarmingUp, w.problem().error)
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertEquals(setOf(RecordingId("recording-1")), w.recordings.recordings, "only the queued file is left")
        w.closeAndAssertStopped()
    }

    @Test
    fun `a queue that cannot store the job shows the storage error`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.returns(Outcome.Err(ServerUnreachable))
        w.pendingJobs.faults.failure = StorageFailed
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(StorageFailed, w.problem().error)
        w.closeAndAssertStopped()
    }

    @Test
    fun `an empty transcript keeps the audio until Retry transcribes it again`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.returnsNext(Outcome.Err(EmptyTranscript))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(EmptyTranscript, w.problem().error)
        assertNotNull(w.problem().keptAudio)
        assertEquals(setOf(RecordingId("recording-1")), w.recordings.recordings)
        w.send(SessionEvent.ActionTapped(BubbleAction.RETRY))
        assertEquals(2, w.speech.transcribeCall.calls.size)
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertTrue(w.recordings.recordings.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `dismissing an empty transcript deletes the kept audio`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.returns(Outcome.Err(EmptyTranscript))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped, SessionEvent.ActionTapped(BubbleAction.DISMISS))
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        assertTrue(w.recordings.recordings.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `a failed or rejected cleanup inserts the rule-based text and records why`() = runTest {
        val cases: List<Pair<() -> Outcome<CleanedText, CleanupError>, CleanupStatus>> = listOf(
            { Outcome.Err(CleanupRejected(GuardVerdict.EmptyReply)) } to CleanupStatus.SKIPPED_GUARD,
            { Outcome.Err(ServerUnreachable) } to CleanupStatus.SKIPPED_ERROR,
            { error("cleaner bug") } to CleanupStatus.SKIPPED_ERROR,
        )
        cases.forEach { (answer, status) ->
            val w = CoordinatorWorld(this)
            w.cleaner.cleanCall.answers { answer() }
            w.start()
            w.dictate()
            assertEquals(Spoken.LONG_BY_RULES, (w.state as SessionState.Done).receipt.text, "$status")
            assertEquals(status, w.history.entries.single().cleanupStatus)
            w.closeAndAssertStopped()
        }
    }

    @Test
    fun `a blank result ends in no speech with the transcript in history`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("   ")))
        w.start()
        w.dictate()
        assertEquals(NoSpeech, w.problem().error)
        w.idle()
        assertEquals(Spoken.LONG, w.history.entries.single().rawTranscript)
        assertEquals("no_speech", w.history.entries.single().errorCode)
        assertTrue(w.recordings.recordings.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `a changed field hands the text off to the clipboard with Paste`() = runTest {
        val w = CoordinatorWorld(this)
        w.inserter.insertCall.returns(Outcome.Err(FieldChanged))
        w.start()
        w.dictate("hello world")
        val done = w.state as SessionState.Done
        assertEquals(InsertedVia.HANDOFF, done.receipt.via)
        assertEquals(setOf(BubbleAction.PASTE), done.actions)
        assertEquals("Hello world.", w.clipboard.content)
        assertEquals(InsertedVia.HANDOFF, w.history.entries.single().insertedVia)
        w.send(SessionEvent.ActionTapped(BubbleAction.PASTE))
        assertEquals(2, w.inserter.insertCall.calls.size)
        w.closeAndAssertStopped()
    }

    @Test
    fun `never use the clipboard keeps the text in history and offers Copy`() = runTest {
        val w = CoordinatorWorld(
            this,
            settings = configuredSettings().let { it.copy(insertion = it.insertion.copy(neverUseClipboard = true)) },
        )
        w.inserter.insertCall.returns(Outcome.Err(FieldChanged))
        w.start()
        w.dictate("hello world")
        assertEquals(InsertedVia.NONE, (w.state as SessionState.Done).receipt.via)
        assertEquals(null, w.clipboard.content)
        w.send(SessionEvent.ActionTapped(BubbleAction.COPY))
        assertEquals("Hello world.", w.clipboard.content)
        w.closeAndAssertStopped()
    }

    @Test
    fun `typing that is not confirmed in time is handed off`() = runTest {
        val w = CoordinatorWorld(this)
        w.inserter.insertCall.delay = 60.seconds
        w.start()
        w.dictate("hello world")
        w.advance(10.seconds)
        assertEquals(InsertedVia.HANDOFF, (w.state as SessionState.Done).receipt.via)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a stop that fails with a storage error leaves the file for recovery`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.stopCall.returns(Outcome.Err(StorageFailed))
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        assertEquals(StorageFailed, w.problem().error)
        w.idle()
        assertEquals(setOf(RecordingId("recording-1")), w.recordings.recordings)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a stop that hangs cancels the recorder and deletes the file`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.stopCall.delay = 60.seconds
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.advance(5.seconds)
        assertEquals(RecorderFailed, w.problem().error)
        assertEquals(1, w.audio.cancelCount)
        w.idle()
        assertTrue(w.recordings.recordings.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `a command that fails ends in a problem without a fallback`() = runTest {
        listOf<Pair<FieldContext, DictationError>>(
            FieldContext("", "", "selected words") to ServerUnreachable,
            FieldContext.Empty to SelectionUnreadable,
        ).forEach { (context, expected) ->
            val w = CoordinatorWorld(this)
            w.contextReader.readCall.returns(context)
            w.cleaner.commandCall.returns(Outcome.Err(ServerUnreachable))
            w.start(focus = false)
            w.focus(selection = TextRange(0, 5))
            w.dictate("make it shorter")
            assertEquals(expected, w.problem().error)
            assertTrue(w.inserter.insertCall.calls.isEmpty())
            w.closeAndAssertStopped()
        }
    }

    @Test
    fun `a problem while the field is gone closes to Hidden`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.returns(Outcome.Err(EmptyTranscript))
        w.speech.transcribeCall.delay = 1.seconds
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.focused.focus(null)
        w.advance(1.seconds)
        assertEquals(EmptyTranscript, w.problem().error)
        w.send(SessionEvent.ActionTapped(BubbleAction.DISMISS))
        assertEquals(SessionState.Hidden, w.state)
        w.closeAndAssertStopped()
    }
}
