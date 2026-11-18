package net.jbglenn.telltype.ui.screens.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.testing.FakeAppRuleRepository
import net.jbglenn.telltype.testing.FakeBubbleSnooze
import net.jbglenn.telltype.testing.FakeClipboard
import net.jbglenn.telltype.testing.FakeDebugBundleExporter
import net.jbglenn.telltype.testing.FakeDictionaryRepository
import net.jbglenn.telltype.testing.FakeFixLauncher
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakeMisrecognitionReporter
import net.jbglenn.telltype.testing.FakePermissionStatusSource
import net.jbglenn.telltype.testing.FakeSelfTest
import net.jbglenn.telltype.testing.FakeServerCertificateReader
import net.jbglenn.telltype.testing.FakeServerConnectionChecker
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.FakeServerModelCatalog
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FakeSnippetRepository
import net.jbglenn.telltype.testing.FakeTextCleaner
import net.jbglenn.telltype.testing.TestDispatcherProvider
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import net.jbglenn.telltype.ui.screens.FixedLocalTime
import net.jbglenn.telltype.ui.screens.diagnostics.DiagnosticsViewModel
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryViewModel
import net.jbglenn.telltype.ui.screens.history.HistoryDeletions
import net.jbglenn.telltype.ui.screens.history.HistoryEraser
import net.jbglenn.telltype.ui.screens.history.HistoryViewModel
import net.jbglenn.telltype.ui.screens.home.HomeViewModel
import net.jbglenn.telltype.ui.screens.settings.ModelsViewModel
import net.jbglenn.telltype.ui.screens.settings.SettingsViewModel
import net.jbglenn.telltype.ui.screens.setup.SetupViewModel
import net.jbglenn.telltype.ui.screens.snippets.SnippetsViewModel
import net.jbglenn.telltype.ui.screens.styles.StylesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val factory = FakeViewModelFactory()

    private lateinit var nav: NavHostController

    private fun start(atSetup: Boolean = false) {
        compose.setContent {
            nav = rememberNavController()
            TelltypePreviewTheme {
                TelltypeNavHost(viewModelFactory = factory, startAtSetup = atSetup, navController = nav)
            }
        }
    }

    private fun barItem(label: String) =
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Test
    fun barMovesBetweenTopLevelScreens() {
        start()
        compose.onNodeWithText("Telltype").assertExists()

        compose.onNode(barItem("History")).performClick()
        compose.onNodeWithText("No dictations yet").assertExists()
        compose.onNode(barItem("History")).assertIsSelected()

        compose.onNode(barItem("Dictionary")).performClick()
        compose.onNodeWithText("No words yet").assertExists()

        compose.onNode(barItem("Snippets")).performClick()
        compose.onNodeWithText("No snippets yet").assertExists()

        compose.onNode(barItem("Home")).performClick()
        compose.onNodeWithText("Telltype").assertExists()
    }

    @Test
    fun afterFirstRunSetupTheBarKeepsOneCopyOfEachScreenAndPopsNothingThatIsGone() {
        start(atSetup = true)
        compose.runOnUiThread {
            // What Setup's onFinish does: Home replaces Setup, the graph's start destination.
            nav.navigate(AppRoute.Home) {
                popUpTo<AppRoute.Setup> { inclusive = true }
                launchSingleTop = true
            }
        }
        compose.onNodeWithText("Telltype").assertExists()
        ShadowLog.clear()

        for (label in listOf("History", "Settings", "Dictionary", "History", "Home")) {
            compose.onNode(barItem(label)).performClick()
            compose.waitForIdle()
        }

        val ignored = ShadowLog.getLogs().filter { it.msg.orEmpty().contains("Ignoring popBackStack") }
        assertEquals(emptyList<String>(), ignored.map { it.msg })
        assertEquals(true, nav.currentBackStackEntry?.destination?.hasRoute(AppRoute.Home::class))
        assertNull("Home is the only screen on the stack", nav.previousBackStackEntry)
    }

    @Test
    fun settingsOpensDiagnosticsAndBackReturns() {
        start()
        compose.onNode(barItem("Settings")).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Diagnostics"))
        compose.onNodeWithText("Diagnostics").performClick()
        compose.onNodeWithText("Permissions and service").assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Permissions and service").assertDoesNotExist()
        compose.onNodeWithText("Your server").assertExists()
    }

    @Test
    fun setupIsFullScreenWithoutTheBar() {
        start(atSetup = true)

        compose.onNodeWithText("Connect to your server").assertExists()
        compose.onNode(barItem("Dictionary")).assertDoesNotExist()
    }
}

/** Creates every screen ViewModel from in-memory fakes, the way `ScreenViewModelFactory` does from Hilt. */
private class FakeViewModelFactory : ViewModelProvider.Factory {
    private val settings = FakeSettingsRepository()
    private val history = FakeHistoryRepository()
    private val dictionary = FakeDictionaryRepository()
    private val permissions = FakePermissionStatusSource()
    private val health = FakeServerHealthSource()
    private val fixLauncher = FakeFixLauncher()
    private val time = FixedLocalTime()

    @Suppress("UNCHECKED_CAST") // Each branch builds exactly the requested class.
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        SetupViewModel::class.java ->
            SetupViewModel(
                settings,
                FakeServerCertificateReader(),
                FakeServerConnectionChecker(),
                health,
                permissions,
                fixLauncher,
            )

        HomeViewModel::class.java ->
            HomeViewModel(permissions, health, settings, history, FakeBubbleSnooze(), fixLauncher, time)

        HistoryViewModel::class.java -> HistoryViewModel(
            history,
            settings,
            dictionary,
            FakeTextCleaner(),
            FakeClipboard(),
            FakeMisrecognitionReporter(),
            HistoryEraser(history, FakeMisrecognitionReporter()),
            HistoryDeletions(HistoryEraser(history, FakeMisrecognitionReporter()), TestDispatcherProvider()),
            time,
        )

        DictionaryViewModel::class.java -> DictionaryViewModel(dictionary)

        SnippetsViewModel::class.java -> SnippetsViewModel(FakeSnippetRepository())

        StylesViewModel::class.java -> StylesViewModel(settings, FakeAppRuleRepository())

        SettingsViewModel::class.java ->
            SettingsViewModel(settings, HistoryEraser(history, FakeMisrecognitionReporter()), time)

        DiagnosticsViewModel::class.java -> DiagnosticsViewModel(
            permissions,
            health,
            history,
            fixLauncher,
            FakeSelfTest(),
            FakeDebugBundleExporter(),
            time,
            settings,
        )

        ModelsViewModel::class.java -> ModelsViewModel(settings, FakeServerModelCatalog())

        else -> error("Unknown ViewModel ${modelClass.name}")
    } as T
}
