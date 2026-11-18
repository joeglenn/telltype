package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.history.ClearHistorySheet
import net.jbglenn.telltype.ui.screens.history.HistoryOffSheet

/**
 * Settings (docs/PLAN.md section 5.3, every row of Appendix G). Stateless apart from which choice row is open.
 *
 * @param showDebugOptions debug builds: also offer the fake engines.
 * @param onNavigate opens the server setup, Styles or Diagnostics.
 * @param models the Models section (D60), from its own ViewModel.
 * @param onModelsAction handles the Models section's actions.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onNavigate: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    showDebugOptions: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    models: ModelsUiState = ModelsUiState(),
    onModelsAction: (ModelsAction) -> Unit = {},
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle: (String) -> (Boolean) -> Unit = { key -> { open -> expanded = if (open) key else null } }
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.settings_title)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { insets ->
        val settings = state.settings
        if (settings == null) {
            LoadingState(modifier = Modifier.padding(insets))
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(modifier = Modifier.screenContent(widthClass).padding(bottom = Spacing.ExtraLarge)) {
                    ServerSection(settings, onNavigate)
                    ModelsSection(models, onModelsAction, expanded, toggle)
                    BubbleSection(settings, onAction, expanded, toggle)
                    DictationSection(settings, onAction, expanded, toggle)
                    CleanupSection(settings, onAction, expanded, toggle, onNavigate)
                    PrivacySection(settings, state.historyCount, onAction, expanded, toggle, onNavigate)
                    AdvancedSection(state, onAction, showDebugOptions, onNavigate)
                }
            }
        }
    }
    if (state.confirmingClear) {
        ClearHistorySheet(
            historyCount = state.unarchivedCount,
            archivedCount = state.archivedCount,
            includeArchive = state.clearIncludesArchive,
            onIncludeArchiveChange = { onAction(SettingsAction.ClearIncludesArchive(it)) },
            onConfirm = { onAction(SettingsAction.ConfirmClearHistory) },
            onDismiss = { onAction(SettingsAction.CancelClearHistory) },
        )
    }
    if (state.confirmingHistoryOff) {
        HistoryOffSheet(
            historyCount = state.unarchivedCount,
            archivedCount = state.archivedCount,
            deleteSaved = state.offDeletesSaved,
            deleteArchive = state.offDeletesArchive,
            onDeleteSavedChange = { onAction(SettingsAction.HistoryOffDeletesSaved(it)) },
            onDeleteArchiveChange = { onAction(SettingsAction.HistoryOffDeletesArchive(it)) },
            onConfirm = { onAction(SettingsAction.ConfirmHistoryOff) },
            onDismiss = { onAction(SettingsAction.CancelHistoryOff) },
        )
    }
}
