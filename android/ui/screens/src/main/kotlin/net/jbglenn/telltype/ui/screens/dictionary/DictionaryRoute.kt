package net.jbglenn.telltype.ui.screens.dictionary

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

/** The Dictionary wired to its ViewModel. */
@Composable
fun DictionaryRoute(
    factory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    viewModel: DictionaryViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            is DictionaryEffect.ShowMessage -> snackbar.showSnackbar(resources.getString(effect.message))
        }
    }
    DictionaryScreen(
        state = state,
        onAction = viewModel::onAction,
        widthClass = widthClass,
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}
