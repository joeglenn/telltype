package net.jbglenn.telltype.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.UnavailableReason

/**
 * What a screen reader hears: the bubble's name, its state as a polite live region (so every state change is
 * announced), a click that does what a tap does, and the snooze actions that touch users reach by dragging.
 */
@Composable
internal fun Modifier.bubbleSemantics(model: BubbleUiModel, onEvent: (BubbleEvent) -> Unit): Modifier {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val name = stringResource(R.string.bubble_name)
    val state = model.stateText()
    val clickLabel = model.clickLabel()
    val hideLabel = stringResource(R.string.bubble_action_hide_hour)
    val neverLabel = stringResource(R.string.bubble_action_never_here)
    val idle = model is BubbleUiModel.Ready || model is BubbleUiModel.Unavailable
    return semantics {
        contentDescription = name
        stateDescription = state
        liveRegion = LiveRegionMode.Polite
        if (clickLabel != null) {
            onClick(label = clickLabel) {
                currentOnEvent(BubbleEvent.Session(SessionEvent.MicPressed))
                true
            }
        }
        if (idle) {
            customActions = listOf(
                CustomAccessibilityAction(hideLabel) {
                    currentOnEvent(BubbleEvent.SnoozeRequested)
                    true
                },
                CustomAccessibilityAction(neverLabel) {
                    currentOnEvent(BubbleEvent.NeverShowInThisApp)
                    true
                },
            )
        }
    }
}

@Composable
private fun BubbleUiModel.stateText(): String = when (this) {
    BubbleUiModel.Hidden -> ""

    is BubbleUiModel.Ready -> readyText()

    is BubbleUiModel.Unavailable -> stringResource(R.string.bubble_state_unavailable, stringResource(reason.text))

    is BubbleUiModel.Arming -> stringResource(R.string.bubble_state_arming)

    is BubbleUiModel.Listening -> if (releaseToCancel) {
        stringResource(R.string.bubble_release_to_cancel)
    } else {
        stringResource(R.string.bubble_state_listening)
    }

    is BubbleUiModel.Working -> label?.let { stringResource(it.text) } ?: stringResource(R.string.bubble_state_working)

    is BubbleUiModel.Done -> stringResource(R.string.bubble_state_done)

    is BubbleUiModel.Problem -> stringResource(R.string.bubble_state_problem, stringResource(message))
}

@Composable
private fun BubbleUiModel.Ready.readyText(): String = when {
    offline -> stringResource(R.string.bubble_state_ready_offline)
    mode == BubbleMode.COMMAND -> stringResource(R.string.bubble_state_ready_command)
    else -> stringResource(R.string.bubble_state_ready)
}

@Composable
private fun BubbleUiModel.clickLabel(): String? = when (this) {
    is BubbleUiModel.Ready -> stringResource(
        if (mode == BubbleMode.COMMAND) R.string.bubble_start_command else R.string.bubble_start_dictation,
    )

    is BubbleUiModel.Unavailable -> stringResource(R.string.bubble_explain)

    else -> null
}

/** The short explanation of each reason. */
internal val UnavailableReason.text: Int
    get() = when (this) {
        UnavailableReason.MICROPHONE_PERMISSION_MISSING -> R.string.bubble_reason_microphone
        UnavailableReason.SERVER_NOT_CONFIGURED -> R.string.bubble_reason_server
    }
