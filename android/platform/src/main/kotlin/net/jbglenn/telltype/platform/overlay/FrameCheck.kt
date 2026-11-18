package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.field.ScreenRect

/**
 * Whether a bubble window that is shown can be seen: its frame, read back from the window manager, is not empty and
 * lies on the display (board B1: on the Pixel the bubble's window was open, shown, and nowhere to be seen); and what
 * the self-check ([BubbleSelfCheck]) does about it. Pure.
 */
internal object FrameCheck {
    /** Why a shown window with [frame] cannot be seen on [screen], or null when it can. */
    fun problem(frame: ScreenRect?, screen: ScreenRect): String? = when {
        frame == null -> "no frame"

        frame.right <= frame.left || frame.bottom <= frame.top -> "empty frame"

        frame.left < screen.left || frame.top < screen.top || frame.right > screen.right ||
            frame.bottom > screen.bottom -> "frame not on the display"

        else -> null
    }

    /**
     * What the self-check does with the bubble in [condition] (null: no window). A finger on the bubble always
     * defers the check: a safeguard never moves a bubble the user is touching. A drag counts as never ended only
     * when no finger is down and it has not moved for [staleDragMs].
     */
    fun verdict(condition: BubbleCondition?, staleDragMs: Long): SelfCheckVerdict = when {
        condition == null || condition.blocked -> SelfCheckVerdict.Fine
        condition.fingerDown -> SelfCheckVerdict.Recheck
        !condition.shown -> SelfCheckVerdict.NotShown
        condition.dragging && condition.sinceLastDragMs < staleDragMs -> SelfCheckVerdict.Recheck
        condition.dragging -> SelfCheckVerdict.StaleDrag
        else -> problem(condition.frame, condition.screen)?.let(SelfCheckVerdict::NotVisible) ?: SelfCheckVerdict.Fine
    }
}

/**
 * What the self-check knows about the bubble window when it runs.
 *
 * @property blocked the screen is locked or a permission dialog is in front (the bubble is meant to be hidden).
 * @property shown the content is shown (visible and touchable).
 * @property fingerDown a finger is on the bubble (between its touch down and up or cancel).
 * @property dragging a drag position is set (no `MoveEnded` yet).
 * @property sinceLastDragMs milliseconds since the drag last moved.
 * @property frame the window's frame as read back, null when unknown.
 * @property screen the display.
 */
internal data class BubbleCondition(
    val blocked: Boolean,
    val shown: Boolean,
    val fingerDown: Boolean,
    val dragging: Boolean,
    val sinceLastDragMs: Long,
    val frame: ScreenRect?,
    val screen: ScreenRect,
)

/** The self-check's decision ([FrameCheck.verdict]). */
internal sealed interface SelfCheckVerdict {
    /** Nothing to do. */
    data object Fine : SelfCheckVerdict

    /** A finger is down or a drag is still live: look again later, change nothing. */
    data object Recheck : SelfCheckVerdict

    /** Open and not blocked, but not shown (its content has no size): worth a warning. */
    data object NotShown : SelfCheckVerdict

    /** A drag with no finger that never ended: end it and put the bubble at its default place. */
    data object StaleDrag : SelfCheckVerdict

    /** Shown but not visible for [problem]: put it at its default place. */
    data class NotVisible(val problem: String) : SelfCheckVerdict
}
