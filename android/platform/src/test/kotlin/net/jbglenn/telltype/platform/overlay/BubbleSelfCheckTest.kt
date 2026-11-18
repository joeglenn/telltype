package net.jbglenn.telltype.platform.overlay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bubble's self-check on virtual time: when it re-places the bubble, how often, and never under a finger. */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleSelfCheckTest {
    private val screen = ScreenRect(0, 0, 1080, 2400)
    private val offScreen = ScreenRect(1100, 1328, 1268, 1496)
    private val onScreen = ScreenRect(891, 1328, 1059, 1496)
    private val logger = RecordingLogger()
    private var current = condition(onScreen)
    private var placements = 0

    @Test
    fun `a bubble off the display is placed again after the delay, at most three times per show`() = runTest {
        val check = selfCheck()
        current = condition(offScreen)
        check.restart()
        advanceTimeBy(BubbleSelfCheck.DELAY_MS - 1)
        runCurrent()
        assertEquals("not before the delay", 0, placements)
        advanceTimeBy(BubbleSelfCheck.DELAY_MS * 10)
        runCurrent()
        assertEquals("three re-placements, then it stops", BubbleSelfCheck.MAX_REPAIRS, placements)
        assertEquals(
            3,
            logger.entries.count {
                it.level == LogLevel.WARN && it.message.startsWith("bubble not visible")
            },
        )
        check.restart()
        advanceTimeBy(BubbleSelfCheck.DELAY_MS + 1)
        runCurrent()
        assertEquals("a new show may place it again", BubbleSelfCheck.MAX_REPAIRS + 1, placements)
        check.cancel()
    }

    @Test
    fun `a finger on the bubble is never overruled, and the check runs again once it lifts`() = runTest {
        val check = selfCheck()
        current = condition(offScreen).copy(fingerDown = true, dragging = true)
        check.restart()
        advanceTimeBy(BubbleSelfCheck.STALE_DRAG_MS * 3)
        runCurrent()
        assertEquals("nothing moved while the finger was down", 0, placements)
        current = condition(onScreen)
        advanceTimeBy(BubbleSelfCheck.DELAY_MS + 1)
        runCurrent()
        assertEquals("a bubble on the display after the drop is left where the user put it", 0, placements)
        assertTrue("no warning", logger.entries.none { it.level == LogLevel.WARN })
    }

    @Test
    fun `a drag with no finger that stood still for the stale time is ended`() = runTest {
        val check = selfCheck()
        current = condition(onScreen).copy(dragging = true, sinceLastDragMs = BubbleSelfCheck.STALE_DRAG_MS)
        check.schedule()
        advanceTimeBy(BubbleSelfCheck.DELAY_MS + 1)
        runCurrent()
        assertEquals(1, placements)
        assertTrue(logger.entries.any { it.message.startsWith("bubble drag never ended") })
        check.cancel()
    }

    private fun TestScope.selfCheck() = BubbleSelfCheck(
        scope = backgroundScope,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        logger = logger,
        condition = { current },
        details = { "frame ${current.frame}" },
        placeAtDefault = {
            placements++
            current = current.copy(dragging = false)
        },
    )

    private fun condition(frame: ScreenRect) = BubbleCondition(
        blocked = false,
        shown = true,
        fingerDown = false,
        dragging = false,
        sinceLastDragMs = 0,
        frame = frame,
        screen = screen,
    )
}
