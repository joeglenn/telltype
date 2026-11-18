package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Transition
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Feeds every representative event to every representative state ([ReducerWorld]): the reducer never throws, and
 * the pairs it ignores (same state, no effects) are exactly the ones declared here, so a new behaviour or a lost
 * one shows up as a difference. Also checks invariants over all pairs: timers are cancelled when their state is
 * left, results of another session are ignored, nothing is saved for an incognito field.
 */
class ReducerExhaustiveTest {
    private val world = ReducerWorld()
    private val allEvents = world.events.keys

    private val otherSession = allEvents.filter { it.endsWith(".stale") }.toSet()
    private val focusMoves = setOf("Focus.none", "Focus.other", "Focus.blocked")
    private val recordingStarted = setOf("RecordingStarted.new", "RecordingStarted.own")

    /** For each state, the events it handles; every other event is ignored on purpose. */
    private val handled: Map<String, Set<String>> = mapOf(
        // Idle: follow the focus; a stray RecordingStarted is cancelled; Hidden has no bubble for a learn chip.
        "Hidden" to setOf("Focus.same", "Focus.other", "Focus.blocked", "LearnAccepted") + recordingStarted,
        "Ready" to setOf("Focus.none", "Focus.other", "Focus.blocked", "MicPressed", "CorrectionDetected") +
            "LearnAccepted" + recordingStarted,
        "Unavailable" to setOf("Focus.none", "Focus.same", "Focus.other", "MicPressed", "Action.FIX") +
            setOf("CorrectionDetected", "LearnAccepted") + recordingStarted,
        // Arming: a tap's release is ignored; a hold's release abandons the start.
        "Arming.tap" to focusMoves + setOf("HoldDetected", "DragStarted", "CancelTapped", "RecordingFailed") +
            setOf("Interrupted", "LearnAccepted") + recordingStarted,
        "Arming.hold" to focusMoves + setOf("MicReleased", "DragStarted", "CancelTapped", "RecordingFailed") +
            setOf("Interrupted", "LearnAccepted") + recordingStarted,
        // Listening: its own recording repeated is ignored; another one is cancelled.
        "Listening.tap" to focusMoves + setOf("HoldDetected", "DragStarted", "FinishTapped", "CancelTapped") +
            setOf("RecordingStarted.new", "Interrupted", "Timer.SESSION_WARNING", "Timer.SESSION_LIMIT") +
            "LearnAccepted",
        "Listening.hold" to focusMoves + setOf("MicReleased", "DragStarted", "FinishTapped", "CancelTapped") +
            setOf("RecordingStarted.new", "Interrupted", "Timer.SESSION_WARNING", "Timer.SESSION_LIMIT") +
            "LearnAccepted",
        // Working: only the result of the running stage counts.
        "Working.finalising" to working("AudioFinalised", "AudioFailed"),
        "Working.transcribing" to working("Transcribed", "TranscriptionFailed"),
        "Working.cleaning" to working("Cleaned", "CleanupFailed"),
        "Working.inserting" to working("Inserted", "InsertionFailed", "HandedOff"),
        // Done: only the offered chips and the owned chip timer; Inserted is the reply to Raw or Paste. Done owns
        // no recording any more, so even a repeat of the session's own RecordingStarted is cancelled.
        "Done.typed" to done("Action.RAW", "Action.UNDO", "Timer.DONE_CHIPS"),
        "Done.handoff" to done("Action.PASTE", "Timer.DONE_CHIPS", "Timer.HANDOFF_LIMIT"),
        "Done.none" to done("Action.COPY", "Timer.DONE_CHIPS", "Timer.HANDOFF_LIMIT"),
        "Done.command" to done("Action.UNDO", "Timer.COMMAND_UNDO"),
        // Problem: only offered actions; the dismiss timer only when Dismiss is the only action; a RecordingStarted
        // is cancelled unless it names the audio kept for Retry.
        "Problem.dismissOnly" to
            problem("Action.DISMISS", "Timer.PROBLEM_DISMISS", "QueueFailed", "RecordingStarted.own"),
        "Problem.keptAudio" to problem("Action.RETRY", "Action.DISMISS", "QueueFailed"),
        "Problem.fix" to problem("Action.FIX", "Action.DISMISS", "QueueFailed", "RecordingStarted.own"),
        "Problem.noSession" to problem("Action.RETRY", "Action.DISMISS", "RecordingStarted.own"),
    )

    private fun working(vararg stage: String) =
        focusMoves + setOf("MicPressed", "CancelTapped", "RecordingStarted.new", "LearnAccepted") + stage

    private fun done(vararg own: String) = focusMoves + setOf("MicPressed") + recordingStarted +
        setOf("CorrectionDetected", "LearnAccepted", "Inserted") + own

    private fun problem(vararg own: String) =
        focusMoves + setOf("MicPressed", "RecordingStarted.new", "LearnAccepted") + own

    private fun ignoredPairs(world: ReducerWorld): Map<String, Set<String>> = world.states.mapValues { (_, state) ->
        world.events.filterValues { event -> reduce(state, event) == Transition(state, emptyList()) }.keys
    }

    @Test
    fun `every state is covered by the declaration`() {
        assertEquals(world.states.keys, handled.keys)
        assertTrue(handled.values.all { allEvents.containsAll(it) }, "a declared event label does not exist")
    }

    @Test
    fun `the ignored pairs are exactly the declared ones`() {
        val actual = ignoredPairs(world)
        world.states.keys.forEach { state ->
            assertEquals((allEvents - handled.getValue(state)).sorted(), actual.getValue(state).sorted(), state)
        }
        val pairs = world.states.size * world.events.size
        println("reducer: ${world.states.size} states x ${world.events.size} events = $pairs pairs")
    }

    @Test
    fun `results of another session are always ignored`() {
        world.states.values.forEach { state ->
            otherSession.forEach { label ->
                assertEquals(Transition(state, emptyList()), reduce(state, world.events.getValue(label)), label)
            }
        }
    }

    @Test
    fun `leaving Listening cancels both session timers`() = forAllPairs { state, event, transition ->
        if (state is SessionState.Listening && transition.state !is SessionState.Listening) {
            assertTrue(SessionEffect.CancelTimer(SessionTimer.SESSION_WARNING) in transition.effects, "$event")
            assertTrue(SessionEffect.CancelTimer(SessionTimer.SESSION_LIMIT) in transition.effects, "$event")
        }
    }

    @Test
    fun `leaving Done cancels its chip timer unless that timer fired`() = forAllPairs { state, event, transition ->
        if (state is SessionState.Done && transition.state !is SessionState.Done) {
            val timer = chipTimer(state)
            val fired = event == SessionEvent.TimerFired(timer)
            assertTrue(fired || SessionEffect.CancelTimer(timer) in transition.effects, "$state $event")
        }
    }

    @Test
    fun `the problem timer runs exactly while a dismiss-only problem shows`() = forAllPairs {
            state,
            event,
            transition,
        ->
        val next = transition.state
        val wasRunning = state is SessionState.Problem && dismissesItself(state)
        val runs = next is SessionState.Problem && dismissesItself(next)
        val scheduled = SessionEffect.ScheduleTimer(SessionTimer.PROBLEM_DISMISS) in transition.effects
        val cancelled = SessionEffect.CancelTimer(SessionTimer.PROBLEM_DISMISS) in transition.effects
        val fired = event == SessionEvent.TimerFired(SessionTimer.PROBLEM_DISMISS)
        when {
            !wasRunning && runs -> assertTrue(scheduled, "$state $event")
            wasRunning && !runs -> assertTrue(cancelled || fired, "$state $event")
            else -> assertTrue(!scheduled && !cancelled, "$state $event")
        }
    }

    @Test
    fun `kept audio is deleted when a problem closes without retrying it`() = forAllPairs { state, event, transition ->
        val kept = (state as? SessionState.Problem)?.keptAudio
        val next = transition.state
        if (kept != null && next !is SessionState.Problem && next !is SessionState.Working) {
            assertTrue(SessionEffect.DeleteRecording(kept.recording) in transition.effects, "$event")
        }
    }

    @Test
    fun `nothing is saved to History for an incognito field`() {
        val incognito = ReducerWorld(Fixtures.fieldTarget(incognito = true))
        incognito.states.values.forEach { state ->
            incognito.events.values.forEach { event ->
                val effects = reduce(state, event).effects
                assertTrue(effects.none { it is SessionEffect.SaveHistory }, "$state $event")
            }
        }
    }

    private fun forAllPairs(check: (SessionState, SessionEvent, Transition) -> Unit) {
        world.states.values.forEach { state ->
            world.events.values.forEach { event -> check(state, event, reduce(state, event)) }
        }
    }
}
