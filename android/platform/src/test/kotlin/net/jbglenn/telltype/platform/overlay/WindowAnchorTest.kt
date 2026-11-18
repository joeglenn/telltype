package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowAnchorTest {
    private val screen = ScreenRect(0, 0, 1080, 2400)

    @Test
    fun `a window in the right half is anchored to the right edge`() {
        val anchor = WindowAnchor.of(WindowPosition(891, 1328), width = 168, screen = screen)
        assertEquals(WindowAnchor(fromRight = true, x = 21), anchor)
        assertEquals(891, anchor.left(168, screen))
    }

    @Test
    fun `a window that grows keeps its right edge, so the docked position needs no move`() {
        val ready = WindowAnchor.of(WindowPosition(1080 - 21 - 168, 1328), width = 168, screen = screen)
        val listening = WindowAnchor.of(WindowPosition(1080 - 21 - 483, 1328), width = 483, screen = screen)
        assertEquals(ready, listening)
        assertEquals(1080 - 21 - 483, ready.left(483, screen))
    }

    @Test
    fun `a window in the left half is anchored to the left edge`() {
        val anchor = WindowAnchor.of(WindowPosition(21, 900), width = 168, screen = screen)
        assertEquals(WindowAnchor(fromRight = false, x = 21), anchor)
        assertEquals(21, anchor.left(483, screen))
    }

    @Test
    fun `the anchor flips where the centre crosses the middle`() {
        assertEquals(false, WindowAnchor.of(WindowPosition(455, 0), width = 168, screen = screen).fromRight)
        assertEquals(true, WindowAnchor.of(WindowPosition(456, 0), width = 168, screen = screen).fromRight)
    }
}
