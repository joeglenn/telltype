package net.jbglenn.telltype.ui.overlay

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** ✕, ✓ and the chips' buttons stay out of the accessibility tree while the bubble's window is not at rest. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WindowAtRestTest {
    @get:Rule
    val compose = createComposeRule()

    private val atRest = mutableStateOf(false)

    private fun show(scene: BubbleScene) {
        compose.setContent {
            CompositionLocalProvider(LocalWindowAtRest provides atRest) {
                TelltypePreviewTheme {
                    Bubble(scene = scene, onEvent = {}, motion = TelltypeMotion(reduced = true))
                }
            }
        }
    }

    @Test
    fun theListeningButtonsAppearOnlyOnceTheWindowIsAtRest() {
        show(
            BubbleScene(BubbleUiModel.Listening(BubbleMode.DICTATE, false, false, null, false), null, BubbleSide.RIGHT),
        )
        listOf("Cancel", "Finish").forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Dictation bubble").assertExists()
        atRest.value = true
        compose.waitForIdle()
        listOf("Cancel", "Finish").forEach { compose.onNodeWithContentDescription(it).assertExists() }
        atRest.value = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Cancel").assertDoesNotExist()
    }

    @Test
    fun theFixButtonAppearsOnlyOnceTheWindowIsAtRest() {
        val reason = UnavailableReason.MICROPHONE_PERMISSION_MISSING
        show(BubbleScene(BubbleUiModel.Unavailable(reason), ChipUiModel.Explanation(reason), BubbleSide.RIGHT))
        compose.onNodeWithText("Fix", ignoreCase = true).assertDoesNotExist()
        atRest.value = true
        compose.waitForIdle()
        compose.onNodeWithText("Fix", ignoreCase = true).assertExists()
    }
}
