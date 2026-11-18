package net.jbglenn.telltype.ui.screens

import androidx.compose.runtime.Composable
import java.time.ZoneId
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.port.SelfTestReport
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.designsystem.R as DesignR
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.screens.diagnostics.DiagnosticsScreen
import net.jbglenn.telltype.ui.screens.diagnostics.DiagnosticsUiState
import net.jbglenn.telltype.ui.screens.diagnostics.SelfTestState
import net.jbglenn.telltype.ui.screens.diagnostics.SpeedSummary
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryEditor
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryScreen
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryTab
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryUiState
import net.jbglenn.telltype.ui.screens.history.HistoryDetailScreen
import net.jbglenn.telltype.ui.screens.history.HistoryDetailState
import net.jbglenn.telltype.ui.screens.history.HistoryScreen
import net.jbglenn.telltype.ui.screens.history.HistoryUiState
import net.jbglenn.telltype.ui.screens.history.HistoryUndo
import net.jbglenn.telltype.ui.screens.history.RerunState
import net.jbglenn.telltype.ui.screens.history.UndoKind
import net.jbglenn.telltype.ui.screens.home.DictationStats
import net.jbglenn.telltype.ui.screens.home.HomeProblem
import net.jbglenn.telltype.ui.screens.home.HomeScreen
import net.jbglenn.telltype.ui.screens.home.HomeUiState
import net.jbglenn.telltype.ui.screens.settings.ModelsUiState
import net.jbglenn.telltype.ui.screens.settings.SettingsScreen
import net.jbglenn.telltype.ui.screens.settings.SettingsUiState
import net.jbglenn.telltype.ui.screens.setup.ConnectionState
import net.jbglenn.telltype.ui.screens.setup.SetupScreen
import net.jbglenn.telltype.ui.screens.setup.SetupStep
import net.jbglenn.telltype.ui.screens.setup.SetupUiState
import net.jbglenn.telltype.ui.screens.snippets.SnippetEditor
import net.jbglenn.telltype.ui.screens.snippets.SnippetsScreen
import net.jbglenn.telltype.ui.screens.snippets.SnippetsUiState
import net.jbglenn.telltype.ui.screens.styles.CategoryTone
import net.jbglenn.telltype.ui.screens.styles.StylesEditor
import net.jbglenn.telltype.ui.screens.styles.StylesScreen
import net.jbglenn.telltype.ui.screens.styles.StylesUiState

/** Every screen in its main states, by name: what the screenshot tests record. */
internal object ScreenSamples {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val granted = Fixtures.permissionsGranted()
    private val missing = granted.copy(accessibilityEnabled = false, notificationsGranted = false)

    private fun setup(state: SetupUiState): @Composable (WidthClass) -> Unit = { width ->
        SetupScreen(state = state, onAction = {}, widthClass = width)
    }

    private fun home(state: HomeUiState): @Composable (WidthClass) -> Unit = { width ->
        HomeScreen(state = state, onAction = {}, widthClass = width)
    }

    private fun history(state: HistoryUiState): @Composable (WidthClass) -> Unit = { width ->
        HistoryScreen(state = state.copy(zone = zone), onAction = {}, widthClass = width)
    }

    private fun detail(state: HistoryDetailState): @Composable (WidthClass) -> Unit = { width ->
        HistoryDetailScreen(
            state = HistoryUiState(entries = SampleData.Entries, detail = state, zone = zone),
            onAction = {},
            onNavigateUp = {},
            widthClass = width,
        )
    }

    private fun dictionary(state: DictionaryUiState): @Composable (WidthClass) -> Unit = { width ->
        DictionaryScreen(state = state, onAction = {}, widthClass = width)
    }

    private fun snippets(state: SnippetsUiState): @Composable (WidthClass) -> Unit = { width ->
        SnippetsScreen(state = state, onAction = {}, widthClass = width)
    }

    private fun styles(state: StylesUiState): @Composable (WidthClass) -> Unit = { width ->
        StylesScreen(state = state, onAction = {}, widthClass = width, onNavigateUp = {})
    }

    private fun clearFromSettings(): @Composable (WidthClass) -> Unit = { width ->
        SettingsScreen(
            state = SettingsUiState(
                SampleData.ConfiguredSettings,
                SampleData.Now,
                zone,
                historyCount = SampleData.Entries.size,
                confirmingClear = true,
                archivedCount = 2,
            ),
            onAction = {},
            onNavigate = {},
            widthClass = width,
        )
    }

    private fun historyOffFromSettings(): @Composable (WidthClass) -> Unit = { width ->
        SettingsScreen(
            state = SettingsUiState(
                SampleData.ConfiguredSettings,
                SampleData.Now,
                zone,
                historyCount = SampleData.Entries.size,
                archivedCount = 2,
                confirmingHistoryOff = true,
                offDeletesSaved = true,
            ),
            onAction = {},
            onNavigate = {},
            widthClass = width,
        )
    }

    private fun settings(
        settings: Settings,
        debug: Boolean = false,
        models: ModelsUiState = SampleData.Models,
    ): @Composable (WidthClass) -> Unit = { width ->
        SettingsScreen(
            models = models,
            state = SettingsUiState(settings, SampleData.Now, zone, historyCount = SampleData.Entries.size),
            onAction = {},
            onNavigate = {},
            widthClass = width,
            showDebugOptions = debug,
        )
    }

    private fun diagnostics(state: DiagnosticsUiState): @Composable (WidthClass) -> Unit = { width ->
        DiagnosticsScreen(state = state.copy(zone = zone), onAction = {}, widthClass = width, onNavigateUp = {})
    }

    private val tones = AppCategory.entries.map { CategoryTone(it, Settings.Defaults.styles.toneFor(it)) }

    private val readyHome = HomeUiState(
        loading = false,
        stats = DictationStats(wordsToday = 1240, typicalWordsPerMinute = 142),
        recent = SampleData.Entries.take(3),
        zone = zone,
    )

    val all: Map<String, @Composable (WidthClass) -> Unit> = linkedMapOf(
        "setup_server" to setup(SetupUiState()),
        "setup_server_invalid" to setup(
            SetupUiState(address = "http://myserver", addressError = R.string.setup_error_address_http),
        ),
        "setup_server_fingerprint" to setup(
            SetupUiState(
                address = "192.0.2.10:8170",
                key = "secret",
                connection = ConnectionState.ConfirmFingerprint(
                    SampleData.Fingerprint,
                    changed = false,
                    address = "192.0.2.10:8170",
                ),
            ),
        ),
        "setup_server_changed" to setup(
            SetupUiState(
                address = "192.0.2.10:8170",
                keySaved = true,
                connection = ConnectionState.ConfirmFingerprint(SampleData.Fingerprint, changed = true),
            ),
        ),
        "setup_server_connected" to setup(
            SetupUiState(
                address = "192.0.2.10:8170",
                keySaved = true,
                connection = ConnectionState.Connected(SampleData.GateConnected),
            ),
        ),
        "setup_server_two_addresses" to setup(
            SetupUiState(
                address = "http://192.168.1.20:18201",
                textAddress = "http://192.168.1.20:18200",
                advancedOpen = true,
                connection = ConnectionState.Connected(SampleData.StockConnected),
            ),
        ),
        "setup_server_http_address" to setup(
            SetupUiState(address = "http://192.168.1.20:8000", connection = ConnectionState.Checking),
        ),
        "setup_server_failed" to setup(
            SetupUiState(
                address = "192.0.2.10:8170",
                key = "secret",
                connection = ConnectionState.Failed(DesignR.string.connection_unreachable),
            ),
        ),
        "setup_server_checking" to setup(
            SetupUiState(
                address = "192.0.2.10:8170",
                key = "secret",
                connection = ConnectionState.Checking,
            ),
        ),
        "setup_microphone" to setup(
            SetupUiState(step = SetupStep.MICROPHONE, permissions = granted.copy(microphoneGranted = false)),
        ),
        "setup_notifications" to setup(SetupUiState(step = SetupStep.NOTIFICATIONS, permissions = granted)),
        "setup_accessibility" to setup(SetupUiState(step = SetupStep.ACCESSIBILITY, permissions = missing)),
        "setup_practice" to setup(SetupUiState(step = SetupStep.PRACTICE, permissions = granted)),
        "home_loading" to home(HomeUiState(zone = zone)),
        "home_ready" to home(readyHome),
        "home_problems" to home(
            readyHome.copy(
                problems = listOf(
                    HomeProblem.ACCESSIBILITY_OFF,
                    HomeProblem.SERVER_UNREACHABLE,
                    HomeProblem.NOTIFICATIONS_OFF,
                ),
            ),
        ),
        "home_key_rejected" to home(readyHome.copy(problems = listOf(HomeProblem.SERVER_KEY_REJECTED))),
        "home_certificate_changed" to home(
            readyHome.copy(problems = listOf(HomeProblem.SERVER_CERTIFICATE_CHANGED)),
        ),
        "home_server_error" to home(readyHome.copy(problems = listOf(HomeProblem.SERVER_ERROR))),
        "home_new" to home(
            HomeUiState(loading = false, problems = listOf(HomeProblem.SERVER_NOT_SET_UP), zone = zone),
        ),
        "home_snoozed" to home(readyHome.copy(snoozedUntil = SampleData.Now + 45.minutes)),
        "history_loading" to history(HistoryUiState()),
        "history_empty" to history(HistoryUiState(entries = emptyList())),
        "history_list" to history(HistoryUiState(entries = SampleData.Entries, total = SampleData.Entries.size)),
        "history_no_results" to history(HistoryUiState(query = "invoice", entries = emptyList())),
        "history_split" to history(
            HistoryUiState(
                entries = SampleData.Entries,
                detail = HistoryDetailState(SessionId("s2"), entry = SampleData.Entries[1]),
            ),
        ),
        "history_off" to history(HistoryUiState(entries = emptyList(), historyOff = true)),
        "history_menu" to history(
            HistoryUiState(entries = SampleData.Entries, total = SampleData.Entries.size, menuOpen = true),
        ),
        "history_clear_confirm" to history(
            HistoryUiState(
                entries = SampleData.Entries,
                total = SampleData.Entries.size,
                confirmingClear = true,
                archivedCount = 2,
            ),
        ),
        "history_swipe_archive" to history(HistoryUiState(entries = SampleData.Entries, total = 4)),
        "history_swipe_delete" to history(HistoryUiState(entries = SampleData.Entries, total = 4)),
        "history_undo" to history(
            HistoryUiState(
                entries = SampleData.Entries.drop(1),
                total = SampleData.Entries.size,
                undo = HistoryUndo(SampleData.Entries[0].id, UndoKind.DELETED),
            ),
        ),
        "history_archive" to history(
            HistoryUiState(
                entries = SampleData.Entries.take(2).map { it.copy(archived = true) },
                total = SampleData.Entries.size,
                archivedCount = 2,
                archive = true,
            ),
        ),
        "history_archive_empty" to history(HistoryUiState(entries = emptyList(), archive = true)),
        "history_menu_archive" to history(
            HistoryUiState(
                entries = SampleData.Entries,
                total = SampleData.Entries.size,
                archivedCount = 2,
                menuOpen = true,
            ),
        ),
        "history_detail_archived" to detail(
            HistoryDetailState(SessionId("s2"), entry = SampleData.Entries[1].copy(archived = true)),
        ),
        "history_detail" to detail(HistoryDetailState(SessionId("s2"), entry = SampleData.Entries[1])),
        "history_detail_rerun" to detail(
            HistoryDetailState(
                SessionId("s5"),
                entry = SampleData.Entries[4],
                rerun = RerunState.Done("kubectl get pods --all-namespaces"),
            ),
        ),
        "history_detail_failed" to detail(HistoryDetailState(SessionId("s4"), entry = SampleData.Entries[3])),
        "history_detail_missing" to detail(HistoryDetailState(SessionId("gone"), missing = true)),
        "dictionary_loading" to dictionary(DictionaryUiState()),
        "dictionary_empty" to dictionary(DictionaryUiState(loading = false)),
        "dictionary_words" to dictionary(
            DictionaryUiState(loading = false, terms = SampleData.Terms, replacements = SampleData.Replacements),
        ),
        "dictionary_corrections" to dictionary(
            DictionaryUiState(
                loading = false,
                tab = DictionaryTab.CORRECTIONS,
                terms = SampleData.Terms,
                replacements = SampleData.Replacements,
            ),
        ),
        "dictionary_editor_error" to dictionary(
            DictionaryUiState(
                loading = false,
                terms = SampleData.Terms,
                editor = DictionaryEditor.Word(
                    editing = null,
                    term = "oslo",
                    soundsLike = "",
                    error = R.string.dictionary_error_word_duplicate,
                ),
            ),
        ),
        "snippets_empty" to snippets(SnippetsUiState(loading = false)),
        "snippets_list" to snippets(SnippetsUiState(loading = false, snippets = SampleData.Snippets)),
        "snippets_editor" to snippets(
            SnippetsUiState(
                loading = false,
                snippets = SampleData.Snippets,
                editor = SnippetEditor(
                    editing = null,
                    trigger = "",
                    text = "",
                    error = R.string.snippets_error_trigger_blank,
                ),
            ),
        ),
        "styles" to styles(
            StylesUiState(
                loading = false,
                tones = tones,
                overrides = SampleData.Rules.take(1),
                excluded = SampleData.Rules.drop(1),
            ),
        ),
        "styles_editor" to styles(
            StylesUiState(
                loading = false,
                tones = tones,
                editor = StylesEditor.Override(app = "com.example.notes", category = null, tone = Tone.FORMAL),
            ),
        ),
        "settings" to settings(SampleData.ConfiguredSettings),
        "settings_unconfigured_debug" to settings(Settings.Defaults, debug = true, models = ModelsUiState()),
        "settings_models" to settings(SampleData.StockSettings, models = SampleData.StockModels),
        "settings_clear_confirm" to clearFromSettings(),
        "settings_privacy" to settings(SampleData.ConfiguredSettings),
        "settings_history_off_confirm" to historyOffFromSettings(),
        "settings_privacy_history_off" to settings(
            SampleData.ConfiguredSettings.copy(
                privacy = SampleData.ConfiguredSettings.privacy.copy(saveHistory = false),
            ),
        ),
        "diagnostics_problems" to diagnostics(
            DiagnosticsUiState(
                permissions = missing,
                reachability = ServerReachability.Unreachable(ServerUnreachable, SampleData.Now),
                sessions = SampleData.Entries,
                selfTest = SelfTestState.Failed(DesignR.string.connection_unreachable),
            ),
        ),
        "diagnostics_healthy" to diagnostics(
            DiagnosticsUiState(
                permissions = granted,
                reachability = SampleData.Reachable,
                sessions = SampleData.Entries,
                speed = SpeedSummary(typical = 1.62.seconds, worst = 7.2.seconds),
                selfTest = SelfTestState.Finished(SelfTestReport(passed = true, timings = SampleData.Timings)),
            ),
        ),
        "diagnostics_empty" to diagnostics(DiagnosticsUiState(permissions = granted)),
    )

    /** Screens captured after scrolling to the node with this text. */
    val scrolledTo: Map<String, String> = mapOf(
        "settings_privacy" to "Clear history now",
        "settings_privacy_history_off" to "Clear history now",
    )

    /** Screens that open a second window (a menu or a sheet): captured as the whole screen, not one root. */
    val withWindows: Set<String> = setOf(
        "history_menu",
        "history_menu_archive",
        "history_clear_confirm",
        "settings_clear_confirm",
        "settings_history_off_confirm",
    )

    /** Screens captured with the first History row held mid-swipe: the fraction of the width, right positive. */
    val swiped: Map<String, Float> = mapOf("history_swipe_archive" to 0.4f, "history_swipe_delete" to -0.4f)

    /** The screens also recorded in the dark theme at font scale 2.0. */
    val darkLargeFont: List<String> = listOf("setup_server", "home_ready", "home_snoozed", "history_detail", "settings")

    /** The screens also recorded at tablet width. */
    val tablet: List<String> = listOf("home_ready", "history_split", "settings")
}
