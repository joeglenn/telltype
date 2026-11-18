package net.jbglenn.telltype.e2e

import android.graphics.Rect
import android.os.SystemClock

/** Bringing the bubble back to its default place, and reading where it settled. */
internal object BubbleVisibility {
    private const val SETTLE_MS = 3_000L
    private const val POLL_MS = 150L

    /** Switches the bubble off and on in the settings, which forgets where it was dragged (board B1). */
    fun forgetDraggedPlace() {
        Arrange.settings { it.copy(bubble = it.bubble.copy(enabled = false)) }
        SystemClock.sleep(POLL_MS * 2)
        Arrange.settings { it.copy(bubble = it.bubble.copy(enabled = true)) }
        SystemClock.sleep(POLL_MS * 2)
    }

    /** The bubble's node bounds once two reads in a row agree (they lag after a state change), or null. */
    fun settledBounds(limit: Long = SETTLE_MS): Rect? {
        val deadline = SystemClock.uptimeMillis() + limit
        var last: Rect? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val now = Bubble.bounds()
            if (now != null && now == last) return now
            last = now
            SystemClock.sleep(POLL_MS)
        }
        return last
    }
}
