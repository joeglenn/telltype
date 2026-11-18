package net.jbglenn.telltype.platform.overlay

import kotlin.math.roundToInt
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.system.BubbleDrop
import net.jbglenn.telltype.core.model.system.BubblePlacement

/**
 * Where the bubble goes (the window host's bookkeeping, apart from the window itself): the edge it docks to, the top
 * the user dragged it to per orientation ([PlacementMemory]) and a drag in progress, with the finger that drives it.
 * Main thread only. Pure apart from [uptimeMillis].
 *
 * @param orientation the current orientation (a `Configuration.orientation` value).
 * @param uptimeMillis the clock that dates the last drag move (for a drag that never ended).
 */
internal class BubblePosition(orientation: () -> Int, private val uptimeMillis: () -> Long) {
    private val memory = PlacementMemory(orientation)
    private var dragging: WindowPosition? = null
    private var finger: WindowPosition? = null

    /** The settings' side, used until the user drags the bubble to the other edge. */
    var defaultSide: BubbleSide = BubbleSide.RIGHT

    /** When the drag last moved ([uptimeMillis]); 0 before the first drag. */
    var lastDragAt: Long = 0L
        private set

    /** The edge the bubble docks to in the current orientation. */
    val side: BubbleSide get() = memory.side(defaultSide)

    /** The top the user dragged the bubble to in this orientation, null at its default place. */
    val rememberedTop: Int? get() = memory.top()

    /** Where a drag in progress has put the window, null without a drag. */
    val dragged: WindowPosition? get() = dragging

    /** Moves a drag by ([dx], [dy]) from where the window is ([from]), kept on the screen. */
    fun dragBy(dx: Float, dy: Float, from: WindowPosition, window: WindowExtent) {
        val start = dragging ?: from
        val moved = WindowPosition(start.x + dx.toInt(), start.y + dy.toInt())
        dragging = BubblePlacer.clamp(moved, window.width, window.height, window.screen)
        lastDragAt = uptimeMillis()
    }

    /** The finger at ([x], [y]) on the screen, which went down at ([grabX], [grabY]) inside the window. */
    fun dragFinger(x: Float, y: Float, grabX: Float, grabY: Float, window: WindowExtent) {
        finger = WindowPosition(x.roundToInt(), y.roundToInt())
        dragging = FingerDrag(grabX, grabY).follow(x, y, window.width, window.height, window.screen)
        lastDragAt = uptimeMillis()
    }

    /**
     * Ends the drag: on the snooze band the drop is [BubbleDrop.SnoozeZone]; elsewhere it docks to the nearer edge
     * and the top is remembered for this orientation. Without a drag (or without a [window]) it stays docked where
     * it was.
     */
    fun drop(window: WindowExtent?): BubbleDrop {
        val released = dragging
        val heldAt = finger
        dragging = null
        finger = null
        return when {
            released == null || window == null -> BubbleDrop.Docked(side)

            BubblePlacer.inSnoozeZone(heldAt?.y ?: (released.y + window.height / 2), window.screen) ->
                BubbleDrop.SnoozeZone

            else -> {
                val nearest = BubblePlacer.nearestSide(released.x + window.width / 2, window.screen)
                memory.remember(nearest, released.y)
                BubbleDrop.Docked(nearest)
            }
        }
    }

    /** Forgets every remembered place and any drag: the bubble goes back to its default place. */
    fun forget() {
        memory.forget()
        cancelDrag()
    }

    /** Drops a drag in progress without remembering anything (the window went away). */
    fun cancelDrag() {
        dragging = null
        finger = null
    }

    /** Where the window should be now: the drag's position, else the default or remembered place for [field]. */
    fun target(window: WindowExtent, margin: Int, field: FieldSnapshot?): WindowPosition =
        dragging ?: BubblePlacer.place(
            PlacementInput(
                screen = window.screen,
                width = window.width,
                height = window.height,
                margin = margin,
                side = side,
                preferredTop = memory.top(),
                field = field?.fieldBounds,
                keyboard = field?.keyboardBounds,
            ),
        )

    /** The placement published for the window at [applied] (the position after the display clamp). */
    fun placement(applied: WindowPosition, window: WindowExtent): BubblePlacement {
        val drag = dragging
        return BubblePlacement(
            x = applied.x,
            y = applied.y,
            side = side,
            dragging = drag != null,
            // The finger decides; for dragBy drags (no finger reported) the bubble's centre.
            inSnoozeZone = drag != null &&
                BubblePlacer.inSnoozeZone(finger?.y ?: (drag.y + window.height / 2), window.screen),
        )
    }
}

/** A window's content size and the display it is on. */
internal data class WindowExtent(val width: Int, val height: Int, val screen: ScreenRect)
