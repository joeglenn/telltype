package net.jbglenn.telltype.ui.overlay

import net.jbglenn.telltype.core.model.settings.BubbleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BubblePlacementTest {
    @Test
    fun snapsToTheNearerEdge() {
        assertEquals(
            BubbleSide.LEFT,
            BubblePlacement.snapSide(bubbleLeft = 100f, bubbleWidth = 132f, screenWidth = 1080f),
        )
        assertEquals(
            BubbleSide.RIGHT,
            BubblePlacement.snapSide(bubbleLeft = 600f, bubbleWidth = 132f, screenWidth = 1080f),
        )
        assertEquals(24f, BubblePlacement.dockedLeft(BubbleSide.LEFT, 132f, 1080f, edgeMargin = 24f))
        assertEquals(924f, BubblePlacement.dockedLeft(BubbleSide.RIGHT, 132f, 1080f, edgeMargin = 24f))
    }

    @Test
    fun snoozeBandHasThreeTargets() {
        fun at(x: Float, y: Float) = BubblePlacement.snoozeTargetAt(x, y, 1080f, 2400f, zoneHeight = 300f)

        assertNull(at(540f, 2000f))
        assertEquals(SnoozeTarget.ONE_HOUR, at(100f, 2300f))
        assertEquals(SnoozeTarget.THIS_APP, at(540f, 2300f))
        assertEquals(SnoozeTarget.NEVER_IN_THIS_APP, at(1000f, 2300f))
    }
}
