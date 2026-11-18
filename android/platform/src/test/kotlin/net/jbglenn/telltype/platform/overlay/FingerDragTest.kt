package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect
import org.junit.Assert.assertEquals
import org.junit.Test

class FingerDragTest {
    private val screen = ScreenRect(0, 0, 1080, 2400)

    /** Pressed 84 px right and 84 px down from the window's corner, the window being at (891, 1328). */
    private val drag = FingerDrag(grabX = 84f, grabY = 84f)

    @Test
    fun theWindowEndsAtItsPressPositionPlusTheFingersTotalOffsetHoweverManyMovesCame() {
        val slow = (1..300).map { step ->
            drag.follow(975f - step * 705f / 300, 1412f - step, 126, 126, screen)
        }

        assertEquals(WindowPosition(891 - 705, 1328 - 300), slow.last())
        assertEquals(
            "one fast move lands on the same place",
            WindowPosition(891 - 705, 1328 - 300),
            drag.follow(270f, 1112f, 126, 126, screen),
        )
    }

    @Test
    fun theWindowStaysOnTheScreen() {
        assertEquals(WindowPosition(0, 2400 - 126), drag.follow(-500f, 3000f, 126, 126, screen))
        assertEquals(WindowPosition(1080 - 126, 0), drag.follow(2000f, 10f, 126, 126, screen))
    }

    @Test
    fun subPixelPositionsRoundToTheNearestPixel() {
        assertEquals(WindowPosition(100, 200), drag.follow(184.4f, 283.6f, 126, 126, screen))
    }
}
