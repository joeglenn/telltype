package net.jbglenn.telltype.ui.screens.diagnostics

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.screens.common.CollectEffects

/** Diagnostics wired to its ViewModel; permissions are re-read whenever it resumes. */
@Composable
fun DiagnosticsRoute(
    factory: ViewModelProvider.Factory,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    viewModel: DiagnosticsViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is DiagnosticsEffect.ShowMessage -> snackbar.showSnackbar(resources.getString(effect.message))
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(DiagnosticsAction.Resumed)
        onPauseOrDispose {}
    }
    DiagnosticsScreen(
        state = state,
        onAction = viewModel::onAction,
        onNavigateUp = onNavigateUp,
        widthClass = widthClass,
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}
