package net.jbglenn.telltype.ui.overlay

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.PillShape
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons

/** Done's chips or Problem's Retry and Fix: 32 dp chips in 48 dp targets, 8 dp apart (Appendix H). */
@Composable
internal fun ActionChipRow(
    actions: BubbleActions,
    alignment: Alignment.Horizontal,
    onEvent: (BubbleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(BubbleTokens.ChipGap, alignment),
        modifier = modifier,
    ) {
        actions.items.forEach { action ->
            BubbleChip(
                label = stringResource(action.label),
                clickLabel = stringResource(action.clickLabel),
                icon = action.icon,
                onClick = { onEvent(BubbleEvent.Session(SessionEvent.ActionTapped(action))) },
            )
        }
    }
}

@Composable
private fun BubbleChip(
    label: String,
    clickLabel: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = ChipElevation,
        modifier = modifier.exposedWhen(rememberExposedAtRest()).minimumInteractiveComponentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = Modifier
                .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
                .heightIn(min = BubbleTokens.ChipHeight)
                .padding(start = Spacing.Small, end = Spacing.Medium),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(ChipIcon))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A transient chip (Busy, one minute left, why the bubble is unavailable, Learn X → Y), announced politely. */
@Composable
internal fun TransientChip(chip: ChipUiModel, onEvent: (BubbleEvent) -> Unit, modifier: Modifier = Modifier) {
    val action: Pair<String, () -> Unit>? = when (chip) {
        ChipUiModel.Busy, ChipUiModel.SessionEndingSoon -> null

        is ChipUiModel.Explanation -> stringResource(R.string.bubble_action_fix) to {
            onEvent(BubbleEvent.Session(SessionEvent.ActionTapped(BubbleAction.FIX)))
        }

        is ChipUiModel.LearnOffer -> stringResource(R.string.bubble_chip_learn_action) to {
            onEvent(BubbleEvent.Session(SessionEvent.LearnAccepted(chip.correction)))
        }
    }
    val exposed = rememberExposedAtRest()
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = ChipElevation,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = Spacing.Large, end = if (action == null) Spacing.Large else 0.dp),
        ) {
            Text(
                text = chip.message(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(vertical = Spacing.Medium)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (action != null) {
                TextButton(
                    onClick = action.second,
                    modifier = Modifier.exposedWhen(exposed),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.inversePrimary,
                    ),
                ) { Text(text = action.first) }
            }
        }
    }
}

@Composable
private fun ChipUiModel.message(): String = when (this) {
    ChipUiModel.Busy -> stringResource(R.string.bubble_chip_busy)
    ChipUiModel.SessionEndingSoon -> stringResource(R.string.bubble_chip_ending_soon)
    is ChipUiModel.Explanation -> stringResource(reason.explanation)
    is ChipUiModel.LearnOffer -> stringResource(R.string.bubble_chip_learn, correction.heard, correction.replaceWith)
}

@get:StringRes
private val UnavailableReason.explanation: Int
    get() = when (this) {
        UnavailableReason.MICROPHONE_PERMISSION_MISSING ->
            R.string.bubble_explain_microphone

        UnavailableReason.SERVER_NOT_CONFIGURED ->
            R.string.bubble_explain_server
    }

@get:StringRes
private val BubbleAction.label: Int
    get() = when (this) {
        BubbleAction.RETRY -> R.string.bubble_action_retry
        BubbleAction.FIX -> R.string.bubble_action_fix
        BubbleAction.COPY -> R.string.bubble_action_copy
        BubbleAction.PASTE -> R.string.bubble_action_paste
        BubbleAction.RAW -> R.string.bubble_action_raw
        BubbleAction.UNDO -> R.string.bubble_action_undo
        BubbleAction.DISMISS -> R.string.bubble_action_dismiss
    }

@get:StringRes
private val BubbleAction.clickLabel: Int
    get() = when (this) {
        BubbleAction.RETRY -> R.string.bubble_click_retry
        BubbleAction.FIX -> R.string.bubble_click_fix
        BubbleAction.COPY -> R.string.bubble_click_copy
        BubbleAction.PASTE -> R.string.bubble_click_paste
        BubbleAction.RAW -> R.string.bubble_click_raw
        BubbleAction.UNDO -> R.string.bubble_click_undo
        BubbleAction.DISMISS -> R.string.bubble_click_dismiss
    }

@get:DrawableRes
private val BubbleAction.icon: Int
    get() = when (this) {
        BubbleAction.RETRY -> TelltypeIcons.Refresh
        BubbleAction.FIX -> TelltypeIcons.Build
        BubbleAction.COPY -> TelltypeIcons.Copy
        BubbleAction.PASTE -> TelltypeIcons.Paste
        BubbleAction.RAW -> TelltypeIcons.Notes
        BubbleAction.UNDO -> TelltypeIcons.Undo
        BubbleAction.DISMISS -> TelltypeIcons.Close
    }

private val ChipElevation = 3.dp
private val ChipIcon = 18.dp
