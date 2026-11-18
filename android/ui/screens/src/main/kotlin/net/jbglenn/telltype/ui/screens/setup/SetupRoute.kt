package net.jbglenn.telltype.ui.screens.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.screens.common.CollectEffects

/** Setup wired to its ViewModel. Every resume re-reads the permissions (back from Android Settings). */
@Composable
fun SetupRoute(
    factory: ViewModelProvider.Factory,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    viewModel: SetupViewModel = viewModel(factory = factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEffects(viewModel.effects) { effect ->
        when (effect) {
            SetupEffect.Finished -> onFinish()
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onAction(SetupAction.Resumed)
        onPauseOrDispose {}
    }
    SetupScreen(state = state, onAction = viewModel::onAction, widthClass = widthClass, modifier = modifier)
}
