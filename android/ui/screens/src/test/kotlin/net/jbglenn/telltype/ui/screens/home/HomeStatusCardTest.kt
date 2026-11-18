package net.jbglenn.telltype.ui.screens.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneId
import kotlin.time.Instant
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeStatusCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val actions = mutableListOf<HomeAction>()

    private val snoozed = HomeUiState(
        loading = false,
        snoozedUntil = Instant.fromEpochSeconds(SNOOZED_UNTIL_SECONDS),
        zone = ZoneId.of("UTC"),
    )

    private fun show(state: HomeUiState) {
        compose.setContent {
            TelltypePreviewTheme { HomeStatusCard(state = state, onAction = { actions += it }) }
        }
    }

    @Test
    fun snoozedSaysTheBubbleIsHiddenAndResumes() {
        show(snoozed)

        compose.onNodeWithText("Bubble hidden until", substring = true).assertExists()
        compose.onAllNodesWithText("Ready to dictate").assertCountEquals(0)
        compose.onNodeWithText("Resume").performClick()

        assertEquals(listOf<HomeAction>(HomeAction.EndSnooze), actions)
    }

    @Test
    fun problemsComeBeforeTheSnooze() {
        show(snoozed.copy(problems = listOf(HomeProblem.MICROPHONE_DENIED)))

        compose.onAllNodesWithText("Bubble hidden until", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Microphone permission is off").assertExists()
    }

    @Test
    fun withTheBubbleSwitchedOffTheSnoozeIsNotShown() {
        show(snoozed.copy(bubbleEnabled = false))

        compose.onAllNodesWithText("Bubble hidden until", substring = true).assertCountEquals(0)
    }

    private companion object {
        const val SNOOZED_UNTIL_SECONDS = 1_800_000_000L
    }
}
