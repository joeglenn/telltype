package net.jbglenn.telltype.testing

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.system.BubbleDrop
import net.jbglenn.telltype.core.model.system.BubblePlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformFakesTest {
    private val docked =
        BubblePlacement(x = 1000, y = 1400, side = BubbleSide.RIGHT, dragging = false, inSnoozeZone = false)

    @Test
    fun `bubble window shows nothing until published and ignores drags meanwhile`() {
        val window = FakeBubbleWindow()

        window.dragBy(10f, 10f)
        assertNull(window.placement.value)
        assertEquals(BubbleDrop.Docked(BubbleSide.RIGHT), window.dragEnded())
        assertEquals(listOf<BubblePlacement?>(null), window.drops)
    }

    @Test
    fun `bubble window follows a drag and docks where the answer says`() {
        val window = FakeBubbleWindow()
        window.publish(docked)

        window.dragBy(-900f, -100f)
        assertEquals(docked.copy(x = 100, y = 1300, dragging = true), window.placement.value)
        window.dropAnswer = { BubbleDrop.Docked(BubbleSide.LEFT) }
        assertEquals(BubbleDrop.Docked(BubbleSide.LEFT), window.dragEnded())
        assertEquals(docked.copy(x = 100, y = 1300, side = BubbleSide.LEFT), window.placement.value)
        assertEquals(listOf(-900f to -100f), window.drags)
    }

    @Test
    fun `bubble window keeps its side after a snooze drop`() {
        val window = FakeBubbleWindow()
        window.publish(docked)
        window.dropAnswer = { BubbleDrop.SnoozeZone }

        assertEquals(BubbleDrop.SnoozeZone, window.dragEnded())
        assertEquals(BubbleSide.RIGHT, window.placement.value?.side)
    }

    @Test
    fun `microphone service runs after a start and stops on stop`() = runTest {
        val service = FakeMicrophoneService()

        assertEquals(Outcome.Ok(Unit), service.start())
        assertTrue(service.running)
        service.stop()
        assertFalse(service.running)
        assertEquals(1, service.stopCount)
    }

    @Test
    fun `microphone service refused by the system does not run`() = runTest {
        val service = FakeMicrophoneService()
        service.startCall.returns(Outcome.Err(RecorderFailed))

        assertEquals(Outcome.Err(RecorderFailed), service.start())
        assertFalse(service.running)
        assertEquals(1, service.startCall.calls.size)
    }
}
