package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.model.settings.BubbleSide

/**
 * Where the user left the bubble, per orientation (docs/PLAN.md section 5.2: "remembered per orientation"). In
 * memory only; the docking side's default comes from the settings. Main thread only.
 *
 * @param orientation the current orientation (a `Configuration.orientation` value).
 */
internal class PlacementMemory(private val orientation: () -> Int) {
    private val remembered = mutableMapOf<Int, Pair<BubbleSide, Int>>()

    /** The edge to dock to in the current orientation. */
    fun side(default: BubbleSide): BubbleSide = remembered[orientation()]?.first ?: default

    /** The top the bubble was dragged to in the current orientation, null when it was not. */
    fun top(): Int? = remembered[orientation()]?.second

    /** Remembers a drop at [top] on [side] for the current orientation. */
    fun remember(side: BubbleSide, top: Int) {
        remembered[orientation()] = side to top
    }

    /** Forgets every remembered place (all orientations): the bubble goes back to its default place. */
    fun forget() {
        remembered.clear()
    }
}
