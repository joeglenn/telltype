package net.jbglenn.telltype.ui.screens.home

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
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.screens.common.CollectEffects

/** Home wired to its ViewModel: state in, actions out, navigation through the callbacks. */
@Composable
fun HomeRoute(
    factory: ViewModelProvider.Factory,
    onOpenSetup: () -> Unit,
    onOpenEntry: (SessionId) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    viewModel: HomeViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            HomeEffect.OpenSetup -> onOpenSetup()
            is HomeEffect.ShowMessage -> snackbar.showSnackbar(resources.getString(effect.message))
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(HomeAction.Resumed)
        onPauseOrDispose {}
    }
    HomeScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is HomeAction.OpenEntry -> onOpenEntry(action.id)
                HomeAction.OpenHistory -> onOpenHistory()
                else -> viewModel.onAction(action)
            }
        },
        widthClass = widthClass,
        snackbarHostState = snackbar,
        modifier = modifier,
    )
}
