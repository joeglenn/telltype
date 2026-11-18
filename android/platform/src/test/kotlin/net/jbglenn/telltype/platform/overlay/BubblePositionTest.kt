package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.system.BubbleDrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where the bubble goes after drags, drops and a reset (board B1: the way back to the default place). */
class BubblePositionTest {
    private val window = WindowExtent(168, 168, ScreenRect(0, 0, 1080, 2400))
    private val position = BubblePosition({ 1 }, { 0L })

    @Test
    fun `a drop docks to the nearer edge and is remembered, a reset forgets it`() {
        val home = position.target(window, MARGIN, null)
        position.dragFinger(150f, 600f, 84f, 84f, window)
        assertEquals(BubbleDrop.Docked(BubbleSide.LEFT), position.drop(window))
        assertEquals(BubbleSide.LEFT, position.side)
        assertEquals(516, position.rememberedTop)
        position.forget()
        assertEquals(BubbleSide.RIGHT, position.side)
        assertNull(position.rememberedTop)
        assertEquals("back at the default place", home, position.target(window, MARGIN, null))
    }

    @Test
    fun `a drag far past the edges stays on the screen`() {
        position.dragBy(5_000f, -5_000f, WindowPosition(891, 1328), window)
        assertEquals(WindowPosition(912, 0), position.dragged)
        assertEquals(WindowPosition(912, 0), position.target(window, MARGIN, null))
    }

    @Test
    fun `a drag cancelled with its window remembers nothing`() {
        position.dragFinger(150f, 600f, 84f, 84f, window)
        position.cancelDrag()
        assertNull(position.dragged)
        assertNull(position.rememberedTop)
    }

    private companion object {
        const val MARGIN = 21
    }
}
