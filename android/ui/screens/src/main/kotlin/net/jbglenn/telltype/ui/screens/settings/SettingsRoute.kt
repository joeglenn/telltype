package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.screens.common.CollectEffects

/** Settings wired to its ViewModel. */
@Composable
fun SettingsRoute(
    factory: ViewModelProvider.Factory,
    onNavigate: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    showDebugOptions: Boolean = false,
    viewModel: SettingsViewModel = viewModel(factory = factory),
    modelsViewModel: ModelsViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by modelsViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is SettingsEffect.ShowMessage -> snackbar.showSnackbar(resources.getString(effect.message))
        }
    }
    SettingsScreen(
        state = state,
        onAction = viewModel::onAction,
        onNavigate = onNavigate,
        widthClass = widthClass,
        showDebugOptions = showDebugOptions,
        snackbarHostState = snackbar,
        models = models,
        onModelsAction = modelsViewModel::onAction,
        modifier = modifier,
    )
}
