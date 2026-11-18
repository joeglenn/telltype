package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.MicIcon
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Bubble with the idle bubble's look rows (board U9): today's look, and a custom colour (which shows the
 * hue and lightness sliders) with a high transparency and another glyph; light, dark and font scale 2.0.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h1400dp-xhdpi")
class BubbleSectionScreenshotTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture("light", dark = false, fontScale = 1f)

    @Test
    fun dark() = capture("dark", dark = true, fontScale = 1f)

    @Test
    fun largeFont() = capture("font2", dark = false, fontScale = 2f)

    private fun capture(variant: String, dark: Boolean, fontScale: Float) {
        val look = Looks.getValue(name)
        val settings = Settings.Defaults.copy(bubble = Settings.Defaults.bubble.copy(look = look))
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            TelltypePreviewTheme(darkTheme = dark, fontScale = fontScale) {
                Box(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    BubbleSection(settings = settings, onAction = {}, expanded = null, toggle = { { } })
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/settings/bubble_${name}_$variant.png")
    }

    companion object {
        private val Looks = linkedMapOf(
            "default" to BubbleLook.Default,
            "custom" to BubbleLook(BubbleColour.Custom(hue = 300, lightness = 35), 60, MicIcon.WAVE),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = Looks.keys.map { arrayOf(it) }
    }
}
