package net.jbglenn.telltype.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.system.PermissionStatus
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.durationText
import net.jbglenn.telltype.ui.screens.history.FactRow

/** Each permission and switch the app depends on, with Fix where it is off. */
@Composable
internal fun PermissionSection(
    permissions: PermissionStatus,
    onAction: (DiagnosticsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.diagnostics_permissions))
        StatusRow(
            R.string.permission_microphone,
            permissions.microphoneGranted,
            FixTarget.MICROPHONE_PERMISSION,
            onAction,
        )
        StatusRow(
            R.string.permission_accessibility,
            permissions.accessibilityEnabled,
            FixTarget.ACCESSIBILITY_SERVICE,
            onAction,
        )
        StatusRow(
            R.string.permission_notifications,
            permissions.notificationsGranted,
            FixTarget.NOTIFICATION_PERMISSION,
            onAction,
        )
        StatusRow(
            R.string.permission_local_network,
            permissions.localNetworkGranted,
            FixTarget.LOCAL_NETWORK_PERMISSION,
            onAction,
        )
        StatusRow(
            name = R.string.permission_advanced_protection,
            good = !permissions.advancedProtectionOn,
            fix = null,
            onAction = onAction,
            goodText = R.string.status_off,
            badText = R.string.status_on_limits,
        )
    }
}

/** One status line: a check or a warning mark (shape, not only colour), the state in words, and Fix. */
@Composable
internal fun StatusRow(
    @StringRes name: Int,
    good: Boolean,
    fix: FixTarget?,
    onAction: (DiagnosticsAction) -> Unit,
    modifier: Modifier = Modifier,
    @StringRes goodText: Int = R.string.status_on,
    @StringRes badText: Int = R.string.status_off,
) {
    ListItem(
        headlineContent = { Text(text = stringResource(name)) },
        supportingContent = { Text(text = stringResource(if (good) goodText else badText)) },
        leadingContent = {
            Icon(
                painter = painterResource(if (good) TelltypeIcons.CheckCircle else TelltypeIcons.Warning),
                contentDescription = null,
                tint = if (good) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = if (!good && fix != null) {
            {
                FilledTonalButton(onClick = { onAction(DiagnosticsAction.Fix(fix)) }) {
                    Text(text = stringResource(R.string.action_fix))
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}

/** Typical and worst stop-to-text time. */
@Composable
internal fun SpeedSection(speed: SpeedSummary?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.diagnostics_speed))
        if (speed == null) {
            Text(
                text = stringResource(R.string.diagnostics_speed_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FactRow(label = stringResource(R.string.diagnostics_speed_typical), value = durationText(speed.typical))
            FactRow(label = stringResource(R.string.diagnostics_speed_worst), value = durationText(speed.worst))
        }
    }
}

/** The self-test and the debug bundle. */
@Composable
internal fun ToolsSection(
    state: DiagnosticsUiState,
    onAction: (DiagnosticsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.diagnostics_tools))
        ToolRow(
            title = stringResource(R.string.diagnostics_self_test),
            supporting = selfTestText(state.selfTest),
            actionLabel = stringResource(R.string.diagnostics_run),
            busy = state.selfTest == SelfTestState.Running,
            onClick = { onAction(DiagnosticsAction.RunSelfTest) },
        )
        ToolRow(
            title = stringResource(R.string.diagnostics_export),
            supporting = stringResource(R.string.diagnostics_export_body),
            actionLabel = stringResource(R.string.diagnostics_export_action),
            busy = state.exporting,
            onClick = { onAction(DiagnosticsAction.Export) },
        )
    }
}

@Composable
private fun ToolRow(
    title: String,
    supporting: String,
    actionLabel: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = Spacing.Small)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Small),
        ) {
            FilledTonalButton(onClick = onClick, enabled = !busy) { Text(text = actionLabel) }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(start = Spacing.Medium).size(ProgressSize))
            }
        }
    }
}

@Composable
private fun selfTestText(state: SelfTestState): String = when (state) {
    SelfTestState.Idle -> stringResource(R.string.diagnostics_self_test_body)

    SelfTestState.Running -> stringResource(R.string.diagnostics_self_test_running)

    is SelfTestState.Failed -> stringResource(R.string.diagnostics_self_test_failed, stringResource(state.message))

    is SelfTestState.Finished -> {
        val total = state.report.timings.endToEnd
        when {
            !state.report.passed -> stringResource(R.string.diagnostics_self_test_mismatch)
            total != null -> stringResource(R.string.diagnostics_self_test_passed_in, durationText(total))
            else -> stringResource(R.string.diagnostics_self_test_passed)
        }
    }
}

private val ProgressSize = 24.dp
