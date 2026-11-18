package net.jbglenn.telltype.core.model.system

import net.jbglenn.telltype.core.model.settings.BubbleSide

/**
 * Where the bubble's window is on screen (docs/PLAN.md sections 5.2 and 7.2), published by the overlay host in
 * `:platform` so the bubble can lay itself out (chips open towards the screen centre) and follow a drag.
 *
 * @property x left edge of the bubble window, screen pixels.
 * @property y top edge of the bubble window, screen pixels.
 * @property side the screen edge the bubble is docked to, or was docked to before the current drag.
 * @property dragging a drag is in progress, so the window follows the finger instead of its docked place.
 * @property inSnoozeZone the bubble is being dragged over the snooze zone at the bottom of the screen;
 *   releasing it there ends in [BubbleDrop.SnoozeZone].
 */
public data class BubblePlacement(
    public val x: Int,
    public val y: Int,
    public val side: BubbleSide,
    public val dragging: Boolean,
    public val inSnoozeZone: Boolean,
)

/** How a drag of the bubble ended. */
public sealed interface BubbleDrop {
    /**
     * Released away from the snooze zone: the bubble snapped to the nearer edge and remembers its height for the
     * current orientation.
     *
     * @property side the edge it snapped to.
     */
    public data class Docked(public val side: BubbleSide) : BubbleDrop

    /** Released over the snooze zone: the caller offers \"hide for an hour or for this app\" (section 5.2). */
    public data object SnoozeZone : BubbleDrop
}
