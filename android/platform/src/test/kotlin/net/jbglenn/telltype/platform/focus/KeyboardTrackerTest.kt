package net.jbglenn.telltype.platform.focus

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.platform.service.AccessibilitySignal
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardTrackerTest {
    private val signals = MutableSharedFlow<AccessibilitySignal>(extraBufferCapacity = 16)
    private val connected = MutableStateFlow(true)
    private val withKeyboard = FocusReading(
        Fixtures.fieldSnapshot(keyboardVisible = true),
        blocked = false,
    )
    private val readings = MutableStateFlow(withKeyboard)
    private var keyboardShown = true
    private var reads = 0
    private val logger = RecordingLogger()

    private fun TestScope.tracker() = KeyboardTracker(
        signals = signals,
        connected = connected,
        readings = readings,
        keyboardShown = {
            reads++
            keyboardShown
        },
        logger = logger,
        scope = backgroundScope,
        uptimeMillis = { 1_005L },
    )

    @Test
    fun `open while the reading has the keyboard`() = runTest {
        val tracker = tracker()
        runCurrent()

        assertTrue(tracker.open.value)
    }

    @Test
    fun `the keyboard going closes it in the same event, without a quiet period`() = runTest {
        val tracker = tracker()
        runCurrent()

        keyboardShown = false
        signals.emit(AccessibilitySignal.KeyboardMayHaveChanged(eventUptimeMillis = 1_000L))
        runCurrent()

        assertFalse("closed before any focus reading changed", tracker.open.value)
        assertEquals(1, reads)
        assertTrue(logger.entries.any { it.message == "keyboard gone: read 5 ms after the event" })
    }

    @Test
    fun `it opens again only once the reading knows where the keyboard is`() = runTest {
        val tracker = tracker()
        runCurrent()
        keyboardShown = false
        signals.emit(AccessibilitySignal.KeyboardMayHaveChanged(1_000L))
        runCurrent()
        readings.value = FocusReading(Fixtures.fieldSnapshot(keyboardVisible = false), blocked = false)
        runCurrent()

        keyboardShown = true
        signals.emit(AccessibilitySignal.KeyboardMayHaveChanged(1_000L))
        runCurrent()
        assertFalse("the reading still has no keyboard", tracker.open.value)

        readings.value = withKeyboard
        advanceTimeBy(1.milliseconds)
        assertTrue(tracker.open.value)
    }

    @Test
    fun `no field, no keyboard`() = runTest {
        readings.value = FocusReading.Nothing
        val tracker = tracker()
        runCurrent()

        assertFalse(tracker.open.value)
    }

    @Test
    fun `focus signals do not read the windows`() = runTest {
        tracker()
        runCurrent()

        signals.emit(AccessibilitySignal.FocusMayHaveChanged)
        signals.emit(AccessibilitySignal.TextChanged(windowId = 3))
        runCurrent()

        assertEquals(0, reads)
    }
}
