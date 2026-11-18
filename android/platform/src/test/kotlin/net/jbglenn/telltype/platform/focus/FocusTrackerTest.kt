package net.jbglenn.telltype.platform.focus

import app.cash.turbine.test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.platform.service.AccessibilitySignal
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusTrackerTest {
    private val signals = MutableSharedFlow<AccessibilitySignal>(extraBufferCapacity = 16)
    private val connected = MutableStateFlow(true)
    private var reading = FocusReading.Nothing
    private var reads = 0
    private val field: FieldSnapshot = Fixtures.fieldSnapshot()

    private fun TestScope.tracker() = FocusTracker(
        signals = signals,
        connected = connected,
        read = {
            reads++
            reading
        },
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        scope = backgroundScope,
        debounce = 80.milliseconds,
    )

    @Test
    fun `reads once on connection, after the quiet period`() = runTest {
        reading = FocusReading(field, blocked = false)
        val tracker = tracker()

        runCurrent()
        assertNull(tracker.focused.value)
        advanceTimeBy(81.milliseconds)
        assertEquals(field, tracker.focused.value)
        assertEquals(1, reads)
    }

    @Test
    fun `a burst of signals is read once`() = runTest {
        val tracker = tracker()
        advanceTimeBy(100.milliseconds)
        reads = 0
        reading = FocusReading(field, blocked = false)

        repeat(5) {
            signals.emit(AccessibilitySignal.FocusMayHaveChanged)
            advanceTimeBy(30.milliseconds)
        }
        advanceTimeBy(100.milliseconds)

        assertEquals(1, reads)
        assertEquals(field, tracker.focused.value)
    }

    @Test
    fun `text changes do not trigger a focus reading`() = runTest {
        tracker()
        advanceTimeBy(100.milliseconds)
        reads = 0

        signals.emit(AccessibilitySignal.TextChanged(windowId = 3))
        advanceTimeBy(200.milliseconds)

        assertEquals(0, reads)
    }

    @Test
    fun `equal readings are published once and leaving the field publishes null`() = runTest {
        reading = FocusReading(field, blocked = false)
        val tracker = tracker()

        tracker.focused.test {
            assertNull(awaitItem())
            assertEquals(field, awaitItem())
            signals.emit(AccessibilitySignal.FocusMayHaveChanged)
            advanceTimeBy(100.milliseconds)
            expectNoEvents()
            reading = FocusReading.Nothing
            signals.emit(AccessibilitySignal.FocusMayHaveChanged)
            assertNull(awaitItem())
        }
    }

    @Test
    fun `a locked screen or permission dialog is null and blocked`() = runTest {
        reading = FocusReading.Blocked
        val tracker = tracker()
        advanceTimeBy(100.milliseconds)

        assertNull(tracker.focused.value)
        assertTrue(tracker.screenBlocked.value)
    }

    @Test
    fun `a disconnected service publishes null without reading, and reconnecting reads again`() = runTest {
        reading = FocusReading(field, blocked = false)
        val tracker = tracker()
        advanceTimeBy(100.milliseconds)
        assertEquals(field, tracker.focused.value)

        connected.value = false
        runCurrent()
        assertNull(tracker.focused.value)
        assertFalse(tracker.screenBlocked.value)
        val readsWhileConnected = reads
        signals.emit(AccessibilitySignal.FocusMayHaveChanged)
        advanceTimeBy(100.milliseconds)
        assertEquals(readsWhileConnected, reads)

        connected.value = true
        advanceTimeBy(100.milliseconds)
        assertEquals(field, tracker.focused.value)
    }
}
