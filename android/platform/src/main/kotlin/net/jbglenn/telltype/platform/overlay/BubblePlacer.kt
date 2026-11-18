package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.settings.BubbleSide

/**
 * What the bubble's position depends on (screen pixels).
 *
 * @property screen the display area.
 * @property width the bubble window's measured width.
 * @property height the bubble window's measured height.
 * @property margin the edge margin (8 dp, Appendix H).
 * @property side the edge to dock to.
 * @property preferredTop the top the user dragged it to in this orientation, null for the default.
 * @property field the focused field, which the bubble must not cover.
 * @property keyboard the keyboard, which the bubble sits above.
 */
internal data class PlacementInput(
    val screen: ScreenRect,
    val width: Int,
    val height: Int,
    val margin: Int,
    val side: BubbleSide,
    val preferredTop: Int?,
    val field: ScreenRect?,
    val keyboard: ScreenRect?,
)

/** A window position: the top-left corner in screen pixels. */
internal data class WindowPosition(val x: Int, val y: Int)

/**
 * Where the bubble goes (docs/PLAN.md section 5.2): docked to an edge, above the keyboard, and moved above (or
 * else below) the focused field when it would cover it. Pure.
 *
 * At its default place (no remembered top) the bubble also keeps out of the field's row across the whole screen
 * width: a chat app's send or microphone button sits beside the field, outside its rectangle, and the bubble docked
 * to the same edge covered it (Telegram on the Pixel, board B2). A top the user dragged it to is kept unless it
 * covers the field itself.
 */
internal object BubblePlacer {
    /** The share of the screen height at the bottom that is the snooze zone. */
    private const val SNOOZE_ZONE_SHARE = 0.12

    /** The docked position for [input]. */
    fun place(input: PlacementInput): WindowPosition {
        val x = when (input.side) {
            BubbleSide.LEFT -> input.screen.left + input.margin
            BubbleSide.RIGHT -> input.screen.right - input.margin - input.width
        }
        val topLimit = input.screen.top + input.margin
        val lowestTop = maxOf(topLimit, (input.keyboard?.top ?: input.screen.bottom) - input.margin - input.height)
        val wanted = (input.preferredTop ?: lowestTop).coerceIn(topLimit, lowestTop)
        val field = input.field
        val avoidRow = input.preferredTop == null
        val y = when {
            field == null || !covers(x, wanted, input, field, avoidRow) -> wanted
            else -> clearOf(field, wanted, input, topLimit, lowestTop)
        }
        return WindowPosition(x, y)
    }

    /** The edge nearer to a bubble whose centre is at [centerX]. */
    fun nearestSide(centerX: Int, screen: ScreenRect): BubbleSide =
        if (centerX < (screen.left + screen.right) / 2) BubbleSide.LEFT else BubbleSide.RIGHT

    /** Whether the screen row [y] (the dragging finger) is in the snooze zone at the bottom of [screen]. */
    fun inSnoozeZone(y: Int, screen: ScreenRect): Boolean =
        y >= screen.bottom - ((screen.bottom - screen.top) * SNOOZE_ZONE_SHARE).toInt()

    /** [position] kept inside [screen] for a window of [width] × [height] (while dragging). */
    fun clamp(position: WindowPosition, width: Int, height: Int, screen: ScreenRect): WindowPosition = WindowPosition(
        x = position.x.coerceIn(screen.left, maxOf(screen.left, screen.right - width)),
        y = position.y.coerceIn(screen.top, maxOf(screen.top, screen.bottom - height)),
    )

    /** Whether a bubble at ([x], [top]) covers [field], or with [wholeRow] the field's row across the screen. */
    private fun covers(x: Int, top: Int, input: PlacementInput, field: ScreenRect, wholeRow: Boolean): Boolean {
        val acrossRow = top < field.bottom && top + input.height > field.top
        return acrossRow && (wholeRow || (x < field.right && x + input.width > field.left))
    }

    /** Above the field when there is room, else below it when that stays above the keyboard, else unchanged. */
    private fun clearOf(field: ScreenRect, wanted: Int, input: PlacementInput, topLimit: Int, lowestTop: Int): Int {
        val above = field.top - input.margin - input.height
        val below = field.bottom + input.margin
        return when {
            above >= topLimit -> above
            below <= lowestTop -> below
            else -> wanted
        }
    }
}
