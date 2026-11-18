package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Cancellation at every stage, and close() during every stage: nothing is left running and nothing late lands. */
class CoordinatorCancellationTest {
    /** After a cancel: no file, no service, no work, and a new dictation runs at once to Done. */
    private fun CoordinatorWorld.assertCleanAndReusable() {
        idle()
        assertTrue(recordings.recordings.isEmpty(), "recordings left: ${recordings.recordings}")
        assertFalse(microphone.running)
        assertNothingRunning()
        speech.transcribeCall.delay = 0.seconds
        cleaner.cleanCall.delay = 0.seconds
        inserter.insertCall.delay = 0.seconds
        microphone.startCall.delay = 0.seconds
        dictate("hello world")
        assertInstanceOf(SessionState.Done::class.java, state)
        closeAndAssertStopped()
    }

    @Test
    fun `cancel while the microphone is starting aborts the start`() = runTest {
        val w = CoordinatorWorld(this)
        w.microphone.startCall.delay = 2.seconds
        w.start()
        w.send(SessionEvent.MicPressed)
        assertInstanceOf(SessionState.Arming::class.java, w.state)
        w.send(SessionEvent.CancelTapped)
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        assertTrue(w.audio.startCall.calls.isEmpty())
        assertEquals(1, w.microphone.stopCount)
        w.assertCleanAndReusable()
    }

    @Test
    fun `a stale start reply racing a cancel and a new press never reaches the reducer`() = runTest {
        val w = CoordinatorWorld(this)
        w.audio.startCall.delay = 1.seconds
        w.start()
        w.send(SessionEvent.MicPressed)
        w.advanceBefore(1.seconds)
        w.coordinator.dispatch(SessionEvent.CancelTapped)
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.runCurrent()
        assertEquals(1, w.audio.cancelCount, "the finished start of session-1 was cancelled")
        assertInstanceOf(SessionState.Arming::class.java, w.state, "session-1's reply was not taken for session-2's")
        w.advance(1.seconds)
        assertEquals(SessionId("session-2"), (w.state as SessionState.Listening).session.id)
        assertEquals(setOf(RecordingId("recording-2")), w.recordings.recordings)
        w.closeAndAssertStopped()
    }

    @Test
    fun `cancel while listening discards the recording`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.CancelTapped)
        assertEquals(1, w.audio.cancelCount)
        assertTrue(w.history.entries.isEmpty())
        w.assertCleanAndReusable()
    }

    @Test
    fun `cancel while uploading cancels the transcription`() = runTest {
        val w = CoordinatorWorld(this)
        w.speech.transcribeCall.delay = 10.seconds
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.advance(1.seconds)
        w.send(SessionEvent.CancelTapped)
        w.advance(20.seconds)
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        assertTrue(w.history.entries.isEmpty())
        assertTrue(w.cleaner.cleanCall.calls.isEmpty())
        w.assertCleanAndReusable()
    }

    @Test
    fun `cancel while cleaning keeps the transcript in history and inserts nothing`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.delay = 2.seconds
        w.start()
        w.dictate()
        w.advance(1.seconds)
        w.send(SessionEvent.CancelTapped)
        w.advance(5.seconds)
        assertTrue(w.inserter.insertCall.calls.isEmpty())
        assertEquals(Spoken.LONG, w.history.entries.single().rawTranscript)
        assertEquals("", w.history.entries.single().finalText)
        w.assertCleanAndReusable()
    }

    @Test
    fun `cancel while inserting drops the late receipt`() = runTest {
        val w = CoordinatorWorld(this)
        w.inserter.insertCall.delay = 3.seconds
        w.start()
        w.dictate("hello world")
        w.advance(1.seconds)
        w.send(SessionEvent.CancelTapped)
        w.advance(5.seconds)
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        assertEquals(1, w.inserter.insertCall.calls.size)
        w.assertCleanAndReusable()
    }

    @Test
    fun `a result of an earlier session is ignored`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.dictate("hello world")
        w.advance(5.seconds)
        w.send(SessionEvent.MicPressed)
        val listening = w.state
        w.send(SessionEvent.Transcribed(SessionId("session-1"), Fixtures.transcript()))
        assertEquals(listening, w.state)
        w.closeAndAssertStopped()
    }

    private fun TestScope.closeDuring(prepare: CoordinatorWorld.() -> Unit, reach: CoordinatorWorld.() -> Unit) {
        val w = CoordinatorWorld(this)
        w.prepare()
        w.start()
        w.reach()
        val calls = w.inserter.insertCall.calls.size
        w.closeAndAssertStopped()
        assertEquals(calls, w.inserter.insertCall.calls.size, "nothing typed after close")
        assertEquals(0, w.coordinator.runningWork())
    }

    @Test
    fun `close during every stage leaves nothing running`() = runTest {
        closeDuring({ microphone.startCall.delay = 5.seconds }) { send(SessionEvent.MicPressed) }
        closeDuring({ audio.startCall.delay = 5.seconds }) { send(SessionEvent.MicPressed) }
        closeDuring({ audio.stopCall.delay = 5.seconds }) { send(SessionEvent.MicPressed, SessionEvent.FinishTapped) }
        closeDuring({ speech.transcribeCall.delay = 5.seconds }) { dictate() }
        closeDuring({ cleaner.cleanCall.delay = 5.seconds }) { dictate() }
        closeDuring({ inserter.insertCall.delay = 5.seconds }) { dictate() }
        closeDuring({}) { dictate() }
        closeDuring({ speech.transcribeCall.returns(Outcome.Err(EmptyTranscript)) }) {
            send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        }
    }

    @Test
    fun `close while listening keeps the recording on disk for recovery`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.send(SessionEvent.MicPressed)
        w.closeAndAssertStopped()
        assertEquals(1, w.audio.stopCall.calls.size)
        assertEquals(setOf(RecordingId("recording-1")), w.recordings.recordings)
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.idle()
        assertEquals(1, w.microphone.startCall.calls.size, "events after close are ignored")
    }
}
