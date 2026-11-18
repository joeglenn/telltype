package net.jbglenn.telltype.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of every bubble state, chip and the long-press menu (docs/PLAN.md section 5.2) in light and dark,
 * each at font scale 1.0 and 2.0.
 * Motion is reduced so that every frame is final and deterministic.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h320dp-xhdpi")
class BubbleScreenshotTest(private val name: String) {
    @Test
    fun light() = capture(Look.LIGHT)

    @Test
    fun dark() = capture(Look.DARK)

    @Test
    fun largeFont() = capture(Look.LARGE_FONT)

    @Test
    fun darkLargeFont() = capture(Look.DARK_LARGE_FONT)

    private fun capture(look: Look) {
        val scene = BubbleScenes.all.getValue(name)
        captureRoboImage("src/test/screenshots/bubble/${name}_${look.suffix}.png") {
            TelltypePreviewTheme(darkTheme = look.dark, fontScale = look.fontScale) {
                BubbleFrame(scene)
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun scenes(): List<Array<Any>> = BubbleScenes.all.keys.map { arrayOf(it) }
    }
}

/** The bubble docked to its edge on a plain surface, the way it sits above a keyboard. */
@Composable
private fun BubbleFrame(scene: BubbleScene) {
    val levels = WaveformLevels().apply { SampleLevels.forEach(::push) }
    Box(
        contentAlignment = if (scene.side == BubbleSide.RIGHT) Alignment.BottomEnd else Alignment.BottomStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Bubble(scene = scene, onEvent = {}, levels = levels, motion = TelltypeMotion(reduced = true))
    }
}

/** A plausible speech envelope for the waveform. */
private val SampleLevels = listOf(0.2f, 0.55f, 0.8f, 0.45f, 0.9f, 0.6f, 0.3f)

/** The four looks every golden is recorded in. */
internal enum class Look(val suffix: String, val dark: Boolean, val fontScale: Float) {
    LIGHT("light", dark = false, fontScale = 1f),
    DARK("dark", dark = true, fontScale = 1f),
    LARGE_FONT("font2", dark = false, fontScale = 2f),
    DARK_LARGE_FONT("dark_font2", dark = true, fontScale = 2f),
}
