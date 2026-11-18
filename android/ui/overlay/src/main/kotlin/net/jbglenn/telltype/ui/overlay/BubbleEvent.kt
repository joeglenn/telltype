package net.jbglenn.telltype.ui.overlay

import net.jbglenn.telltype.core.model.session.SessionEvent

/**
 * Everything the bubble reports. Session gestures go to the reducer; the others are for the window that hosts
 * the bubble (it belongs to `:platform`), which the bubble never touches.
 */
sealed interface BubbleEvent {
    /**
     * A gesture or chip for the session reducer.
     *
     * @property event the event.
     */
    data class Session(val event: SessionEvent) : BubbleEvent

    /**
     * Hold-to-talk feedback: whether the held finger has slid far enough away that releasing cancels. Feed it back
     * as [BubbleInput.releaseToCancel].
     *
     * @property releaseToCancel true once the finger is beyond the cancel distance.
     */
    data class HoldFeedback(val releaseToCancel: Boolean) : BubbleEvent

    /**
     * The bubble is being dragged. Screen pixels for the finger: the window moves with the drag, so positions in
     * the bubble's own coordinates would count the window's lag again on every move. The finger went down at
     * ([grabX], [grabY]) inside the window that hosts the bubble; putting the window's top-left corner at
     * ([x] - [grabX], [y] - [grabY]) (its position when the finger went down plus the finger's total offset) keeps
     * the bubble under the finger, however the window moved or lagged since. ([x], [y]) also picks the snooze
     * target.
     *
     * @property x the finger's horizontal screen position.
     * @property y the finger's vertical screen position.
     * @property grabX where the finger went down, horizontally, in the hosting window's pixels.
     * @property grabY where the finger went down, vertically, in the hosting window's pixels.
     */
    data class Moved(val x: Float, val y: Float, val grabX: Float, val grabY: Float) : BubbleEvent

    /** The drag ended: snap to an edge ([snapToEdge]) or snooze if dropped in the snooze zone. */
    data object MoveEnded : BubbleEvent

    /**
     * The bubble's measured size changed; the window sizes itself to this.
     *
     * @property width width in pixels.
     * @property height height in pixels.
     */
    data class Resized(val width: Int, val height: Int) : BubbleEvent

    /** "Hide for 1 hour" (accessibility action; touch users drag to the snooze zone). */
    data object SnoozeRequested : BubbleEvent

    /** "Never show in this app" (accessibility action, bubble menu, or the snooze zone's third target). */
    data object NeverShowInThisApp : BubbleEvent

    /**
     * A long press asked for the bubble menu ("Hide for 1 hour", "Never show in this app", "Close"). Only a press
     * that cannot start dictation does this: see [net.jbglenn.telltype.ui.overlay.gesture.PressTracker]
     * for the gesture rule. [BubbleOverlay] opens the menu by itself; a host that uses [Bubble] directly feeds it
     * back as [BubbleInput.menuOpen].
     */
    data object MenuRequested : BubbleEvent

    /** The bubble menu was closed without a choice. */
    data object MenuDismissed : BubbleEvent
}
