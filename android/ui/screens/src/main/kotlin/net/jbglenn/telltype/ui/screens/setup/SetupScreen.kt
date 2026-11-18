package net.jbglenn.telltype.ui.screens.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R

/**
 * Setup (docs/PLAN.md section 5.3): five steps with a progress bar, Back and Next. Stateless; system Back goes to
 * the previous step before it leaves setup.
 */
@Composable
fun SetupScreen(
    state: SetupUiState,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
) {
    val first = state.step == SetupStep.entries.first()
    BackHandler(enabled = !first) { onAction(SetupAction.Back) }
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.setup_title)) },
        bottomBar = { SetupNavigation(state, onAction, widthClass) },
        modifier = modifier,
    ) { insets ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.Large),
                modifier = Modifier
                    .screenContent(widthClass)
                    .padding(vertical = Spacing.Large),
            ) {
                StepProgress(step = state.step)
                when (state.step) {
                    SetupStep.SERVER -> ServerStep(state, onAction)
                    SetupStep.MICROPHONE -> MicrophoneStep(state, onAction)
                    SetupStep.NOTIFICATIONS -> NotificationsStep(state, onAction)
                    SetupStep.ACCESSIBILITY -> AccessibilityStep(state, onAction)
                    SetupStep.PRACTICE -> PracticeStep()
                }
            }
        }
    }
}

@Composable
private fun StepProgress(step: SetupStep, modifier: Modifier = Modifier) {
    val total = SetupStep.entries.size
    val number = step.ordinal + 1
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = modifier) {
        Text(
            text = stringResource(R.string.setup_step_of, number, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(progress = { number.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(step.title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun SetupNavigation(state: SetupUiState, onAction: (SetupAction) -> Unit, widthClass: WidthClass) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = widthClass.margin, vertical = Spacing.Medium),
        ) {
            if (state.step != SetupStep.entries.first()) {
                TextButton(onClick = {
                    onAction(SetupAction.Back)
                }) { Text(text = stringResource(R.string.setup_back)) }
            } else {
                Spacer(modifier = Modifier)
            }
            Button(onClick = { onAction(SetupAction.Next) }, enabled = state.canContinue) {
                Text(text = stringResource(nextLabel(state)))
            }
        }
    }
}

private fun nextLabel(state: SetupUiState): Int = when {
    state.step == SetupStep.PRACTICE -> R.string.setup_finish
    state.stepDone || state.step == SetupStep.SERVER || state.step == SetupStep.MICROPHONE -> R.string.setup_next
    else -> R.string.setup_skip
}

private val SetupStep.title: Int
    get() = when (this) {
        SetupStep.SERVER -> R.string.setup_server_title
        SetupStep.MICROPHONE -> R.string.setup_microphone_title
        SetupStep.NOTIFICATIONS -> R.string.setup_notifications_title
        SetupStep.ACCESSIBILITY -> R.string.setup_accessibility_title
        SetupStep.PRACTICE -> R.string.setup_practice_title
    }
