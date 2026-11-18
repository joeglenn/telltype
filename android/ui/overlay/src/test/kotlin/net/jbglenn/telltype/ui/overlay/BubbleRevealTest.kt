package net.jbglenn.telltype.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ready becoming Listening with full motion: the pill takes its final size, and ✕ and ✓ their final places, in the
 * first frame that has them; only the drawing grows (review item "first tap in a fresh process", brief A7f). A
 * layout that grows with the animation resizes the overlay window on every frame, and the window frame lags behind,
 * so the buttons were reported and hit beside where they were drawn.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class BubbleRevealTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theButtonsAreAtRestFromTheFirstFrame() {
        val scene = mutableStateOf(BubbleScene(READY, null, BubbleSide.RIGHT))
        compose.setContent {
            val end = scene.value.side == BubbleSide.RIGHT
            TelltypePreviewTheme {
                Box(
                    contentAlignment = if (end) Alignment.BottomEnd else Alignment.BottomStart,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Bubble(scene = scene.value, onEvent = {}, motion = TelltypeMotion(reduced = false))
                }
            }
        }
        BubbleSide.entries.forEach { side ->
            compose.mainClock.autoAdvance = true
            scene.value = BubbleScene(READY, null, side)
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            scene.value = BubbleScene(LISTENING, null, side)
            var frames = 0
            while (compose.onAllNodesWithContentDescription(CANCEL).fetchSemanticsNodes().isEmpty()) {
                check(++frames <= MAX_FRAMES) { "$side: no ✕ after $MAX_FRAMES frames" }
                compose.mainClock.advanceTimeByFrame()
            }
            val first = bounds()
            compose.mainClock.advanceTimeBy(SETTLED_MS)
            assertEquals("$side: bubble, ✕ and ✓ after the animation", first, bounds())
        }
    }

    private fun bounds(): List<DpRect> = listOf(BUBBLE, CANCEL, FINISH).map {
        compose.onNodeWithContentDescription(it).getBoundsInRoot()
    }

    private companion object {
        const val BUBBLE = "Dictation bubble"
        const val CANCEL = "Cancel"
        const val FINISH = "Finish"
        const val SETTLED_MS = 2_000L
        const val MAX_FRAMES = 10
        val READY = BubbleUiModel.Ready(BubbleMode.DICTATE, offline = false)
        val LISTENING = BubbleUiModel.Listening(BubbleMode.DICTATE, false, false, null, false)
    }
}
