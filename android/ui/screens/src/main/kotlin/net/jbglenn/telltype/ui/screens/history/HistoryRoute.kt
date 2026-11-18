package net.jbglenn.telltype.ui.screens.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.screens.common.CollectEffects

/**
 * History wired to its ViewModel. On phones selecting an entry calls [onOpenEntry]; from 840 dp it opens in the
 * detail pane.
 */
@Composable
fun HistoryRoute(
    factory: ViewModelProvider.Factory,
    onOpenEntry: (SessionId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    viewModel: HistoryViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is HistoryEffect.ShowMessage -> snackbar.showSnackbar(resources.getString(effect.message))
            HistoryEffect.EntryDeleted -> Unit
        }
    }
    HistoryScreen(
        state = state,
        onAction = { action ->
            when {
                action is HistoryAction.Select && !widthClass.showsTwoPanes -> onOpenEntry(action.id)
                action == HistoryAction.OpenSettings -> onOpenSettings()
                else -> viewModel.onAction(action)
            }
        },
        widthClass = widthClass,
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}

/** One History entry on its own page, wired to its ViewModel; leaves the page when the entry is deleted. */
@Composable
fun HistoryDetailRoute(
    factory: ViewModelProvider.Factory,
    id: SessionId,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    viewModel: HistoryViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, id) { viewModel.onAction(HistoryAction.Select(id)) }
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is HistoryEffect.ShowMessage -> snackbar.showSnackbar(resources.getString(effect.message))
            HistoryEffect.EntryDeleted -> onNavigateUp()
        }
    }
    HistoryDetailScreen(
        state = state,
        onAction = viewModel::onAction,
        onNavigateUp = onNavigateUp,
        widthClass = widthClass,
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}
