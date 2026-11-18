package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The self-check's judgement of a shown bubble window's frame (board B1). */
class FrameCheckTest {
    private val screen = ScreenRect(0, 0, 1080, 2400)

    @Test
    fun `a frame on the display passes`() {
        assertNull(FrameCheck.problem(ScreenRect(891, 1328, 1059, 1496), screen))
        assertNull(FrameCheck.problem(screen, screen))
    }

    @Test
    fun `a frame off the display, partly or wholly, is reported`() {
        assertEquals("frame not on the display", FrameCheck.problem(ScreenRect(1100, 1328, 1268, 1496), screen))
        assertEquals("frame not on the display", FrameCheck.problem(ScreenRect(1000, 1328, 1168, 1496), screen))
        assertEquals("frame not on the display", FrameCheck.problem(ScreenRect(21, -200, 189, -32), screen))
        assertEquals("frame not on the display", FrameCheck.problem(ScreenRect(21, 2300, 189, 2468), screen))
    }

    @Test
    fun `an empty or missing frame is reported`() {
        assertEquals("empty frame", FrameCheck.problem(ScreenRect(891, 1328, 891, 1496), screen))
        assertEquals("empty frame", FrameCheck.problem(ScreenRect(891, 1328, 1059, 1328), screen))
        assertEquals("no frame", FrameCheck.problem(null, screen))
    }

    @Test
    fun `a finger on the bubble defers every check, even of a frame off the display`() {
        val off = condition(frame = ScreenRect(1100, 1328, 1268, 1496), fingerDown = true)
        assertEquals(SelfCheckVerdict.Recheck, FrameCheck.verdict(off, STALE))
        assertEquals(
            SelfCheckVerdict.Recheck,
            FrameCheck.verdict(off.copy(dragging = true, sinceLastDragMs = 60_000), STALE),
        )
    }

    @Test
    fun `a drag ends only without a finger and after it stood still for the stale time`() {
        val drag = condition().copy(dragging = true, sinceLastDragMs = STALE - 1)
        assertEquals(SelfCheckVerdict.Recheck, FrameCheck.verdict(drag, STALE))
        assertEquals(SelfCheckVerdict.StaleDrag, FrameCheck.verdict(drag.copy(sinceLastDragMs = STALE), STALE))
    }

    @Test
    fun `a bubble that should not be seen or has no window is left alone, one not shown is reported`() {
        assertEquals(SelfCheckVerdict.Fine, FrameCheck.verdict(null, STALE))
        assertEquals(SelfCheckVerdict.Fine, FrameCheck.verdict(condition(frame = null, blocked = true), STALE))
        assertEquals(SelfCheckVerdict.NotShown, FrameCheck.verdict(condition(shown = false), STALE))
        assertEquals(SelfCheckVerdict.Fine, FrameCheck.verdict(condition(), STALE))
        assertEquals(
            SelfCheckVerdict.NotVisible("empty frame"),
            FrameCheck.verdict(condition(frame = ScreenRect(891, 1328, 891, 1496)), STALE),
        )
    }

    private fun condition(
        frame: ScreenRect? = ScreenRect(891, 1328, 1059, 1496),
        fingerDown: Boolean = false,
        blocked: Boolean = false,
        shown: Boolean = true,
    ) = BubbleCondition(
        blocked = blocked,
        shown = shown,
        fingerDown = fingerDown,
        dragging = false,
        sinceLastDragMs = 0,
        frame = frame,
        screen = screen,
    )

    private companion object {
        const val STALE = 10_000L
    }
}
