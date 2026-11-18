package net.jbglenn.telltype.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The explanation chip with its Fix button (microphone permission missing) on a bubble docked to either edge, at
 * font scale 1.0 and 2.0, on a phone-sized screen: the chip opens from the docked edge towards the middle and stays
 * on the screen.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class BubbleChipPlacementTest(private val side: BubbleSide, private val fontScale: Float) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theExplanationChipOpensTowardsTheMiddleAndStaysOnTheScreen() {
        val reason = UnavailableReason.MICROPHONE_PERMISSION_MISSING
        val scene = BubbleScene(BubbleUiModel.Unavailable(reason), ChipUiModel.Explanation(reason), side)
        compose.setContent {
            TelltypePreviewTheme(fontScale = fontScale) {
                Box(
                    contentAlignment = if (side == BubbleSide.RIGHT) Alignment.BottomEnd else Alignment.BottomStart,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Bubble(scene = scene, onEvent = {}, motion = TelltypeMotion(reduced = true))
                }
            }
        }
        val screen = compose.onRoot().getBoundsInRoot()
        val bubble = compose.onNodeWithContentDescription(BUBBLE).getBoundsInRoot()
        val explanation = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.bubble_explain_microphone)
        val text = compose.onNodeWithText(explanation, useUnmergedTree = true).getBoundsInRoot()
        val fix = compose.onNodeWithText(FIX, ignoreCase = true, useUnmergedTree = true).getBoundsInRoot()
        val where = "$side at font scale $fontScale: screen $screen, bubble $bubble, text $text, Fix $fix"
        listOf(text, fix).forEach { part ->
            assertTrue("on the screen: $where", part.left >= screen.left && part.right <= screen.right)
        }
        if (side == BubbleSide.RIGHT) {
            assertTrue("opens leftwards from the docked edge: $where", text.left < bubble.left)
            assertTrue("ends at the docked edge: $where", fix.right <= bubble.right)
        } else {
            assertTrue("starts at the docked edge: $where", text.left >= bubble.left)
            assertTrue("opens rightwards from the docked edge: $where", fix.right > bubble.right)
        }
    }

    companion object {
        private const val BUBBLE = "Dictation bubble"
        private const val FIX = "Fix"

        /** Both edges at font scale 1.0 and 2.0. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} at {1}")
        fun cases(): List<Array<Any>> =
            BubbleSide.entries.flatMap { side -> listOf(1f, 2f).map { arrayOf<Any>(side, it) } }
    }
}
