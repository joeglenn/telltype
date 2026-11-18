package net.jbglenn.telltype.ui.screens

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import net.jbglenn.telltype.ui.designsystem.WidthClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Golden images of every screen in its main states on a phone, in light, dark and at font scale 2.0. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class ScreenScreenshotTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture("light", dark = false, fontScale = 1f)

    @Test
    fun dark() = capture("dark", dark = true, fontScale = 1f)

    @Test
    fun largeFont() = capture("font2", dark = false, fontScale = 2f)

    private fun capture(look: String, dark: Boolean, fontScale: Float) {
        val screen = ScreenSamples.all.getValue(name)
        // Menus and sheets open their own windows, which take the font scale from the configuration.
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            TelltypePreviewTheme(darkTheme = dark, fontScale = fontScale) { screen(WidthClass.COMPACT) }
        }
        capture(compose, name, "src/test/screenshots/phone/${name}_$look.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = ScreenSamples.all.keys.map { arrayOf(it) }
    }
}

/** Setup (server step), Home, History detail and Settings on a phone in the dark theme at font scale 2.0. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class DarkLargeFontScreenshotTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun darkLargeFont() {
        val screen = ScreenSamples.all.getValue(name)
        compose.setContent {
            TelltypePreviewTheme(darkTheme = true, fontScale = 2f) { screen(WidthClass.COMPACT) }
        }
        capture(compose, name, "src/test/screenshots/phone/${name}_dark_font2.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = ScreenSamples.darkLargeFont.map { arrayOf(it) }
    }
}

/** Home, History and Settings on a tablet in landscape, light and dark. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class TabletScreenshotTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture("light", dark = false)

    @Test
    fun dark() = capture("dark", dark = true)

    private fun capture(look: String, dark: Boolean) {
        val screen = ScreenSamples.all.getValue(name)
        compose.setContent {
            TelltypePreviewTheme(darkTheme = dark) { screen(WidthClass.EXPANDED) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/tablet/${name}_$look.png")
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun names(): List<Array<Any>> = ScreenSamples.tablet.map { arrayOf(it) }
    }
}

/** One root (scrolled first where the sample asks), or the whole screen for samples with a second window. */
@OptIn(ExperimentalRoborazziApi::class) // captureScreenRoboImage is the only capture that includes popup windows.
private fun capture(compose: ComposeContentTestRule, name: String, path: String) {
    ScreenSamples.scrolledTo[name]?.let { text -> compose.onNode(hasScrollAction()).performScrollToNode(hasText(text)) }
    ScreenSamples.swiped[name]?.let { fraction ->
        // Held, not released: the row stays where the finger is, its action showing behind it.
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasScrollToIndexAction()))[0].performTouchInput {
            down(center)
            moveBy(Offset(width * fraction / 2, 0f))
            moveBy(Offset(width * fraction / 2, 0f))
        }
        compose.waitForIdle()
    }
    if (name in ScreenSamples.withWindows) {
        compose.waitForIdle()
        captureScreenRoboImage(path)
    } else {
        compose.onRoot().captureRoboImage(path)
    }
}
