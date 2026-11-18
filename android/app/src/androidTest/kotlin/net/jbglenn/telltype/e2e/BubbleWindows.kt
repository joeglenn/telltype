package net.jbglenn.telltype.e2e

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * The bubble as the window manager and the screen see it, not only as its accessibility node says (board B1: on the
 * Pixel the app believed it showed the bubble while nothing was on the screen).
 */
internal object BubbleWindows {
    private const val BUBBLE_TITLE = "Telltype bubble"
    private const val TRAY_TITLE = "Telltype snooze tray"

    /** One overlay window of the app: its title, frame and whether the window manager shows it. */
    data class Frame(val title: String, val frame: Rect, val visible: Boolean)

    /** Every overlay window of the app from `dumpsys window windows` (the bubble's and the snooze tray's). */
    fun all(): List<Frame> = Device.shell("dumpsys window windows").split("Window #").mapNotNull { block ->
        val title = listOf(BUBBLE_TITLE, TRAY_TITLE).firstOrNull { block.contains("$it}") } ?: return@mapNotNull null
        val frame = Regex("""frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]""").find(block)?.groupValues
            ?.drop(1)?.map(String::toInt) ?: return@mapNotNull null
        Frame(title, Rect(frame[0], frame[1], frame[2], frame[3]), block.contains("isVisible=true"))
    }

    /** The bubble's window, or null when there is none. */
    fun bubble(): Frame? = all().firstOrNull { it.title == BUBBLE_TITLE }

    /** The snooze tray's window, or null when there is none. */
    fun tray(): Frame? = all().firstOrNull { it.title == TRAY_TITLE }

    /**
     * How many dark pixels (the microphone glyph) the screen shows in the middle of [frame], where the Ready
     * bubble draws its icon: hundreds when the bubble is drawn there, about none on an empty light background. The
     * light, translucent circle itself is too close to a light background to tell apart. Null without a picture.
     */
    fun glyphPixels(frame: Rect): Int? {
        val shot: Bitmap = Device.uiAutomation.takeScreenshot() ?: return null
        val middle = Rect(
            frame.centerX() - frame.width() / GLYPH_FRACTION,
            frame.centerY() - frame.height() / GLYPH_FRACTION,
            frame.centerX() + frame.width() / GLYPH_FRACTION,
            frame.centerY() + frame.height() / GLYPH_FRACTION,
        )
        // A box wholly off the picture (a frame off the screen) has no glyph pixels.
        val dark = if (middle.intersect(0, 0, shot.width, shot.height)) darkPixels(shot, middle) else 0
        shot.recycle()
        return dark
    }

    /** How many pixels of [shot] inside [box] are dark (the glyph). */
    private fun darkPixels(shot: Bitmap, box: Rect): Int {
        var dark = 0
        for (y in box.top until box.bottom) {
            for (x in box.left until box.right) {
                val pixel = shot.getPixel(x, y)
                val luma = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                if (luma < DARK_LUMA) dark++
            }
        }
        return dark
    }

    /** The middle box is the frame's size divided by this, on each side of the centre. */
    private const val GLYPH_FRACTION = 5

    /** Below this brightness (0 to 255) a pixel belongs to the glyph. */
    private const val DARK_LUMA = 140
}
