package net.jbglenn.telltype.ui.screens.setup

import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The server key is kept away from autofill; the local-network row stays once allowed. */
@RunWith(AndroidJUnit4::class)
class SetupStepsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theKeyFieldIsHiddenFromAutofillServices() {
        compose.setContent { TelltypePreviewTheme { ServerStep(state = SetupUiState(), onAction = {}) } }

        val password = SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)
        compose.onNode(
            password and SemanticsMatcher.expectValue(SemanticsProperties.ContentDataType, ContentDataType.None),
        )
            .assertExists()
    }

    @Test
    fun anAllowedLocalNetworkPermissionStaysAsAllowed() {
        microphoneStep(localNetworkGranted = true, applies = true)

        compose.onNodeWithText("Local network").assertExists()
        compose.onAllNodesWithText("Allowed").assertCountEquals(2)
    }

    @Test
    fun aMissingLocalNetworkPermissionShowsItsRow() {
        microphoneStep(localNetworkGranted = false, applies = true)

        compose.onNodeWithText("Local network").assertExists()
        compose.onNodeWithText("Not allowed yet").assertExists()
    }

    @Test
    fun belowAndroid17ThereIsNoLocalNetworkRow() {
        microphoneStep(localNetworkGranted = true, applies = false)

        compose.onNodeWithText("Local network").assertDoesNotExist()
    }

    @Test
    fun theLocalNetworkPermissionExistsFromAndroid17() {
        assertFalse(LocalNetwork.applies(36))
        assertTrue(LocalNetwork.applies(37))
    }

    private fun microphoneStep(localNetworkGranted: Boolean, applies: Boolean) {
        val permissions = Fixtures.permissionsGranted().copy(localNetworkGranted = localNetworkGranted)
        compose.setContent {
            TelltypePreviewTheme {
                MicrophoneStep(
                    state = SetupUiState(permissions = permissions),
                    onAction = {},
                    localNetworkApplies = applies,
                )
            }
        }
    }
}
