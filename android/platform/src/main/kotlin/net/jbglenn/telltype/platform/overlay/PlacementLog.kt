package net.jbglenn.telltype.platform.overlay

import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.settings.BubbleSide

/**
 * Logs every placement of the bubble that differs from the one logged last, at info level (board B1: the next time
 * the bubble is gone, the app's log file says where it was). Numbers and sides only. Main thread only.
 */
internal class PlacementLogger(private val logger: Logger) {
    private var logged: PlacementLine? = null

    /** Logs [line] unless it is the one logged last. */
    fun placed(line: PlacementLine) {
        if (line == logged) return
        logged = line
        logger.info(TAG) { "bubble placed: $line" }
    }

    /** The window was removed: the next placement is logged whatever it is. */
    fun forget() {
        logged = null
    }

    private companion object {
        val TAG = LogTag("OverlayHost")
    }
}

/** What a placement log line shows (numbers and sides only). */
internal data class PlacementLine(
    val side: BubbleSide,
    val position: WindowPosition,
    val window: WindowExtent,
    val keyboardTop: Int?,
    val rememberedTop: Int?,
) {
    override fun toString(): String =
        "side $side, x ${position.x}, y ${position.y}, ${window.width}x${window.height}, screen " +
            "${window.screen.right - window.screen.left}x${window.screen.bottom - window.screen.top}, " +
            "keyboard top ${keyboardTop ?: "none"}, " +
            if (rememberedTop == null) "default place" else "dragged top $rememberedTop"
}
