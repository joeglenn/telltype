package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.settings.BubbleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePlacerTest {
    private val screen = ScreenRect(0, 0, 1080, 2400)
    private val keyboard = ScreenRect(0, 1500, 1080, 2400)
    private val base = PlacementInput(
        screen = screen,
        width = 126,
        height = 126,
        margin = 21,
        side = BubbleSide.RIGHT,
        preferredTop = null,
        field = null,
        keyboard = null,
    )

    @Test
    fun `docks to the right or left edge with the margin`() {
        assertEquals(1080 - 21 - 126, BubblePlacer.place(base).x)
        assertEquals(21, BubblePlacer.place(base.copy(side = BubbleSide.LEFT)).x)
    }

    @Test
    fun `sits just above the keyboard, or above the screen bottom without one`() {
        assertEquals(1500 - 21 - 126, BubblePlacer.place(base.copy(keyboard = keyboard)).y)
        assertEquals(2400 - 21 - 126, BubblePlacer.place(base).y)
    }

    @Test
    fun `a remembered height is kept but never below the keyboard top or above the screen`() {
        assertEquals(600, BubblePlacer.place(base.copy(keyboard = keyboard, preferredTop = 600)).y)
        assertEquals(1353, BubblePlacer.place(base.copy(keyboard = keyboard, preferredTop = 2000)).y)
        assertEquals(21, BubblePlacer.place(base.copy(keyboard = keyboard, preferredTop = -50)).y)
    }

    @Test
    fun `moves above a chat input that sits right above the keyboard`() {
        val chatInput = ScreenRect(0, 1340, 1080, 1490)

        val position = BubblePlacer.place(base.copy(keyboard = keyboard, field = chatInput))

        assertEquals(1340 - 21 - 126, position.y)
        assertFalse(overlaps(position, chatInput))
    }

    @Test
    fun `moves below a field when there is no room above it`() {
        val topField = ScreenRect(0, 30, 1080, 900)

        val position = BubblePlacer.place(base.copy(keyboard = keyboard, field = topField, preferredTop = 100))

        assertEquals(900 + 21, position.y)
        assertFalse(overlaps(position, topField))
    }

    @Test
    fun `stays put when the field fills all the room between the top and the keyboard`() {
        val editor = ScreenRect(0, 0, 1080, 1500)

        assertEquals(1353, BubblePlacer.place(base.copy(keyboard = keyboard, field = editor)).y)
    }

    @Test
    fun `at its default place the bubble keeps out of the field's row, where a send button sits beside the field`() {
        // A chat app's message field, right above the keyboard, with its microphone button to the right of it: the
        // bubble docked right covered the button, outside the field's rectangle (board B2).
        val messageField = ScreenRect(0, 1360, 900, 1490)

        val position = BubblePlacer.place(base.copy(keyboard = keyboard, field = messageField))

        assertEquals(1360 - 21 - 126, position.y)
        assertTrue("above the row", position.y + base.height <= messageField.top)
    }

    @Test
    fun `a place the user dragged it to beside a field is kept`() {
        val narrow = ScreenRect(0, 1300, 500, 1450)

        assertEquals(1320, BubblePlacer.place(base.copy(keyboard = keyboard, field = narrow, preferredTop = 1320)).y)
    }

    @Test
    fun `the nearer edge and the snooze zone`() {
        assertEquals(BubbleSide.LEFT, BubblePlacer.nearestSide(539, screen))
        assertEquals(BubbleSide.RIGHT, BubblePlacer.nearestSide(540, screen))
        assertTrue(BubblePlacer.inSnoozeZone(y = 2263, screen = screen))
        assertFalse(BubblePlacer.inSnoozeZone(y = 1963, screen = screen))
    }

    @Test
    fun `a drag stays on screen`() {
        assertEquals(WindowPosition(954, 0), BubblePlacer.clamp(WindowPosition(2000, -40), 126, 126, screen))
        assertEquals(WindowPosition(0, 2274), BubblePlacer.clamp(WindowPosition(-5, 9999), 126, 126, screen))
    }

    @Test
    fun `placement memory is per orientation`() {
        var orientation = 1
        val memory = PlacementMemory { orientation }
        memory.remember(BubbleSide.LEFT, 400)

        assertEquals(BubbleSide.LEFT, memory.side(BubbleSide.RIGHT))
        assertEquals(400, memory.top())
        orientation = 2
        assertEquals(BubbleSide.RIGHT, memory.side(BubbleSide.RIGHT))
        assertEquals(null, memory.top())
    }

    @Test
    fun `forgetting the placement memory brings back the default side and top in every orientation`() {
        var orientation = 1
        val memory = PlacementMemory { orientation }
        memory.remember(BubbleSide.LEFT, 400)
        orientation = 2
        memory.remember(BubbleSide.LEFT, 200)

        memory.forget()

        assertEquals(BubbleSide.RIGHT, memory.side(BubbleSide.RIGHT))
        assertEquals(null, memory.top())
        orientation = 1
        assertEquals(BubbleSide.RIGHT, memory.side(BubbleSide.RIGHT))
        assertEquals(null, memory.top())
    }

    private fun overlaps(position: WindowPosition, field: ScreenRect): Boolean =
        position.x < field.right && position.x + base.width > field.left &&
            position.y < field.bottom && position.y + base.height > field.top
}
