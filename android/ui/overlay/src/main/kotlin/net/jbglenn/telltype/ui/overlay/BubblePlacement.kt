package net.jbglenn.telltype.ui.overlay

import net.jbglenn.telltype.core.model.settings.BubbleSide

/**
 * Where a dragged bubble settles (docs/PLAN.md section 5.2): it snaps to the nearer screen edge, with the edge
 * margin of Appendix H. Pure arithmetic in pixels, for the window that hosts the bubble.
 */
object BubblePlacement {
    /** The edge nearer to the bubble's centre. */
    fun snapSide(bubbleLeft: Float, bubbleWidth: Float, screenWidth: Float): BubbleSide =
        if (bubbleLeft + bubbleWidth / 2f < screenWidth / 2f) BubbleSide.LEFT else BubbleSide.RIGHT

    /** The window's left coordinate when docked to [side]. */
    fun dockedLeft(side: BubbleSide, bubbleWidth: Float, screenWidth: Float, edgeMargin: Float): Float = when (side) {
        BubbleSide.LEFT -> edgeMargin
        BubbleSide.RIGHT -> screenWidth - bubbleWidth - edgeMargin
    }

    /**
     * Which snooze target a bubble dropped with its centre at ([centreX], [centreY]) lands on, or null outside the
     * snooze band at the bottom of the screen. The band is split in three: an hour, this app, never in this app.
     */
    fun snoozeTargetAt(
        centreX: Float,
        centreY: Float,
        screenWidth: Float,
        screenHeight: Float,
        zoneHeight: Float,
    ): SnoozeTarget? = when {
        centreY < screenHeight - zoneHeight -> null
        centreX < screenWidth / THIRDS -> SnoozeTarget.ONE_HOUR
        centreX < screenWidth * 2f / THIRDS -> SnoozeTarget.THIS_APP
        else -> SnoozeTarget.NEVER_IN_THIS_APP
    }

    private const val THIRDS = 3f
}

/** The three drop targets of the snooze band, left to right. */
enum class SnoozeTarget {
    /** Hide the bubble everywhere for an hour. */
    ONE_HOUR,

    /** Hide the bubble in the app in front until that app is left. */
    THIS_APP,

    /** Add the app in front to the exclusion list ("Never show in this app"). */
    NEVER_IN_THIS_APP,
}
