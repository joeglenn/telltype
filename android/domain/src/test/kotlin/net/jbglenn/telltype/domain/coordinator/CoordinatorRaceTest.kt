package net.jbglenn.telltype.domain.coordinator

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.domain.port.HapticsPort
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ordering, races, deadlines and robustness: events while effects run, quick taps, late replies, bad ports. */
class CoordinatorRaceTest {
    @Test
    fun `two quick taps start one recording`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.runCurrent()
        assertEquals(1, w.microphone.startCall.calls.size)
        assertEquals(1, w.audio.startCall.calls.size)
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `events arriving while effects run are processed in order`() = runTest {
        val w = CoordinatorWorld(this)
        val other = Fixtures.fieldTarget(handle = "field-2")
        w.speech.transcribeCall.delay = 2.seconds
        w.start()
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.focus(other)
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.runCurrent()
        assertEquals(listOf<Chip>(Chip.Busy, Chip.Busy), w.chips)
        val working = w.state as SessionState.Working
        assertEquals(other, (working.focus as Focus.Available).field)
        w.advance(2.seconds)
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `dispatch is safe from another thread and ignored after close`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        val thread = Thread { w.coordinator.dispatch(SessionEvent.MicPressed) }
        thread.start()
        thread.join()
        w.runCurrent()
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        w.closeAndAssertStopped()
        w.coordinator.dispatch(SessionEvent.FinishTapped)
        w.coordinator.close()
        w.coordinator.start()
        w.idle()
        assertInstanceOf(SessionState.Listening::class.java, w.state)
    }

    @Test
    fun `a full inbox drops gestures without blocking or throwing`() = runTest {
        val w = CoordinatorWorld(this, config = CoordinatorConfig(inboxCapacity = 2))
        repeat(10) { w.coordinator.dispatch(SessionEvent.CancelTapped) }
        assertEquals(8, w.logger.entries.count { "inbox full" in it.message })
        w.start()
        w.dictate("hello world")
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a restarted chip timer does not fire at the old time`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("Ship it.")))
        w.start()
        w.dictate()
        w.advance(4900.milliseconds)
        w.send(SessionEvent.ActionTapped(BubbleAction.RAW))
        w.advance(4800.milliseconds)
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.advance(200.milliseconds)
        assertInstanceOf(SessionState.Ready::class.java, w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `cleanup slower than the deadline gives the rule-based text and its late reply is ignored`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.delay = 4.seconds
        w.start()
        w.dictate()
        w.advance(2499.milliseconds)
        assertInstanceOf(SessionState.Working::class.java, w.state)
        w.advance(1.milliseconds)
        assertEquals(Spoken.LONG_BY_RULES, (w.state as SessionState.Done).receipt.text)
        w.advance(2.seconds)
        assertEquals(1, w.inserter.insertCall.calls.size)
        assertEquals(CleanupStatus.SKIPPED_TIMEOUT, w.history.entries.single().cleanupStatus)
        w.closeAndAssertStopped()
    }

    @Test
    fun `cleanup within the deadline is applied and always wait waits`() = runTest {
        listOf(2.seconds to 2.5.seconds, 30.seconds to Duration.INFINITE).forEach { (delay, deadline) ->
            val w = CoordinatorWorld(
                this,
                settings = configuredSettings().let { it.copy(cleanup = it.cleanup.copy(modelDeadline = deadline)) },
            )
            w.cleaner.cleanCall.delay = delay
            w.start()
            w.dictate()
            w.advance(delay - 1.milliseconds)
            assertInstanceOf(SessionState.Working::class.java, w.state)
            assertInstanceOf(Stage.Cleaning::class.java, (w.state as SessionState.Working).stage)
            w.advance(1.milliseconds)
            assertEquals("Hello world.", (w.state as SessionState.Done).receipt.text)
            assertEquals(CleanupStatus.APPLIED, w.history.entries.single().cleanupStatus)
            w.closeAndAssertStopped()
        }
    }

    @Test
    fun `a stray cancellation thrown by a port counts as an error, not as a cancel`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.answers { throw CancellationException("leaked timeout") }
        w.start()
        w.dictate()
        assertEquals(Spoken.LONG_BY_RULES, (w.state as SessionState.Done).receipt.text)
        assertEquals(CleanupStatus.SKIPPED_ERROR, w.history.entries.single().cleanupStatus)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a port that throws from a plain call does not break the session`() = runTest {
        val broken = object : HapticsPort {
            override fun play(cue: FeedbackCue): Unit = error("vibrator gone")
        }
        val w = CoordinatorWorld(this, haptics = broken)
        w.start()
        w.dictate("hello world")
        assertInstanceOf(SessionState.Done::class.java, w.state)
        assertTrue(w.logger.entries.any { "haptic threw IllegalStateException" in it.message })
        w.closeAndAssertStopped()
    }
}
