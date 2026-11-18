package net.jbglenn.telltype.platform.overlay

import kotlin.math.roundToInt
import net.jbglenn.telltype.core.model.field.ScreenRect

/**
 * A drag of the bubble window that follows the finger in screen coordinates: the finger went down at
 * ([grabX], [grabY]) inside the window, so the window's top-left corner goes to the finger minus that point (its
 * position at the press plus the finger's total offset). Each position depends only on the latest finger
 * position, so the lag between a requested and the real window position is never added again (a relative "move
 * by" from the last request overshot: a slow 300 px drag ended near the top of the screen), and a window that
 * moved between the press and the drag (placement after a state change) does not shift the bubble off the finger.
 * Pure.
 *
 * @property grabX where the finger went down, horizontally, in window pixels.
 * @property grabY where the finger went down, vertically, in window pixels.
 */
internal data class FingerDrag(val grabX: Float, val grabY: Float) {
    /**
     * The window position for a finger at ([x], [y]) on the screen, kept inside [screen] for a window of
     * [width] × [height].
     */
    fun follow(x: Float, y: Float, width: Int, height: Int, screen: ScreenRect): WindowPosition =
        BubblePlacer.clamp(WindowPosition((x - grabX).roundToInt(), (y - grabY).roundToInt()), width, height, screen)
}
