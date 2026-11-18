package net.jbglenn.telltype.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of the snooze band while a bubble is dragged over it, at the bottom of a window tall enough for
 * the band to take the height its labels need (the band is never given less than that in use).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h320dp-xhdpi")
class SnoozeTrayScreenshotTest(private val highlighted: String) {
    @Test
    fun light() = capture(Look.LIGHT)

    @Test
    fun dark() = capture(Look.DARK)

    @Test
    fun largeFont() = capture(Look.LARGE_FONT)

    @Test
    fun darkLargeFont() = capture(Look.DARK_LARGE_FONT)

    private fun capture(look: Look) {
        val target = SnoozeTarget.entries.firstOrNull { it.name == highlighted }
        captureRoboImage("src/test/screenshots/snooze/${highlighted.lowercase()}_${look.suffix}.png") {
            TelltypePreviewTheme(darkTheme = look.dark, fontScale = look.fontScale) {
                Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
                    SnoozeTray(highlighted = target)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun targets(): List<Array<Any>> = (SnoozeTarget.entries.map { it.name } + "NONE").map { arrayOf(it) }
    }
}
