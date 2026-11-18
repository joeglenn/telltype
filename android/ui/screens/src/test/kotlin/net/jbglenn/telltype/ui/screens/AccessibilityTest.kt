package net.jbglenn.telltype.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import net.jbglenn.telltype.ui.designsystem.WidthClass
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every control on every sample screen is labelled and has a 48 dp touch target, also at font scale 2.0. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun controlsAreLabelledAndLargeEnough() = checkAll(fontScale = 1f)

    @Test
    fun controlsAreLabelledAndLargeEnoughAtFontScaleTwo() = checkAll(fontScale = 2f)

    private fun checkAll(fontScale: Float) {
        val current = mutableStateOf(ScreenSamples.all.keys.first())
        compose.setContent {
            TelltypePreviewTheme(fontScale = fontScale) {
                ScreenSamples.all.getValue(current.value)(WidthClass.COMPACT)
            }
        }
        val minimum = with(compose.density) { 48.dp.toPx() } - 0.5f
        val problems = mutableListOf<String>()
        ScreenSamples.all.keys.forEach { name ->
            current.value = name
            compose.waitForIdle()
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { node ->
                val config = node.config
                val label = listOfNotNull(
                    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(),
                    config.getOrNull(SemanticsProperties.Text)?.joinToString(),
                    config.getOrNull(SemanticsProperties.EditableText)?.text,
                ).joinToString()
                val bounds = node.touchBoundsInRoot
                val visible = bounds.height > 0f && bounds.width > 0f
                if (visible && (bounds.height < minimum || bounds.width < minimum)) {
                    problems += "$name: '$label' is ${bounds.width / compose.density.density}x" +
                        "${bounds.height / compose.density.density} dp"
                }
                if (visible && label.isBlank()) problems += "$name: unlabelled control at $bounds"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
