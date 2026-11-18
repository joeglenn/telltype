package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect

/**
 * The edge an overlay window's horizontal offset is counted from. A window in the right half of the screen is
 * anchored to the right edge (gravity END), so content that grows (the Ready circle becoming the Listening pill)
 * widens the window towards the middle without a move, and a window in the left half to the left edge.
 *
 * @property fromRight the offset is counted from the right edge.
 * @property x the offset from that edge, in pixels.
 */
internal data class WindowAnchor(val fromRight: Boolean, val x: Int) {
    /** The left edge of a window of [width] with this anchor on [screen]. */
    fun left(width: Int, screen: ScreenRect): Int = if (fromRight) screen.right - x - width else x

    companion object {
        /** The anchor that puts a window of [width] at [position] on [screen]. */
        fun of(position: WindowPosition, width: Int, screen: ScreenRect): WindowAnchor {
            val fromRight = position.x + width / 2 >= (screen.left + screen.right) / 2
            return WindowAnchor(fromRight, if (fromRight) screen.right - position.x - width else position.x)
        }
    }
}
