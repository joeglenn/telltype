package net.jbglenn.telltype.ui.screens.setup

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.ErrorBanner
import net.jbglenn.telltype.ui.screens.R

/**
 * Step 2: the microphone, and on Android 17 the local network. The local-network row stays once allowed and then
 * reads "Allowed", like the microphone row.
 *
 * @param localNetworkApplies the system has the local-network permission (Android 17 and later).
 */
@Composable
internal fun MicrophoneStep(
    state: SetupUiState,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
    localNetworkApplies: Boolean = LocalNetwork.applies(Build.VERSION.SDK_INT),
) {
    val permissions = state.permissions
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = modifier) {
        Text(text = stringResource(R.string.setup_microphone_body), style = MaterialTheme.typography.bodyMedium)
        PermissionCard(
            name = R.string.permission_microphone,
            granted = permissions?.microphoneGranted == true,
            target = FixTarget.MICROPHONE_PERMISSION,
            onAction = onAction,
        )
        if (localNetworkApplies || permissions?.localNetworkGranted == false) {
            Text(text = stringResource(R.string.setup_local_network_body), style = MaterialTheme.typography.bodyMedium)
            PermissionCard(
                name = R.string.permission_local_network,
                granted = permissions?.localNetworkGranted == true,
                target = FixTarget.LOCAL_NETWORK_PERMISSION,
                onAction = onAction,
            )
        }
    }
}

/** Where the local-network permission exists. */
internal object LocalNetwork {
    /** The first SDK level with `ACCESS_LOCAL_NETWORK` (Android 17). */
    const val FIRST_SDK = 37

    /** The permission exists on [sdkInt]. */
    fun applies(sdkInt: Int): Boolean = sdkInt >= FIRST_SDK
}

/** Step 3: notifications (optional). */
@Composable
internal fun NotificationsStep(state: SetupUiState, onAction: (SetupAction) -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = modifier) {
        Text(text = stringResource(R.string.setup_notifications_body), style = MaterialTheme.typography.bodyMedium)
        PermissionCard(
            name = R.string.permission_notifications,
            granted = state.permissions?.notificationsGranted == true,
            target = FixTarget.NOTIFICATION_PERMISSION,
            onAction = onAction,
        )
    }
}

/** Step 4: the accessibility service, with the "restricted settings" walk-through for sideloaded installs. */
@Composable
internal fun AccessibilityStep(state: SetupUiState, onAction: (SetupAction) -> Unit, modifier: Modifier = Modifier) {
    val permissions = state.permissions
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = modifier) {
        Text(text = stringResource(R.string.setup_accessibility_body), style = MaterialTheme.typography.bodyMedium)
        if (permissions?.advancedProtectionOn == true) {
            ErrorBanner(message = stringResource(R.string.setup_advanced_protection))
        }
        PermissionCard(
            name = R.string.permission_accessibility,
            granted = permissions?.accessibilityEnabled == true,
            target = FixTarget.ACCESSIBILITY_SERVICE,
            onAction = onAction,
        )
        if (permissions?.accessibilityEnabled != true) RestrictedSettingsHelp()
    }
}

/** Step 5: a field to try the real bubble in. What is typed here stays on this screen only. */
@Composable
internal fun PracticeStep(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = modifier) {
        Text(text = stringResource(R.string.setup_practice_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(text = stringResource(R.string.setup_practice_field)) },
            minLines = PracticeDefaults.PRACTICE_LINES,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One permission: a check or a warning mark, its state in words, and Allow while it is missing. */
@Composable
private fun PermissionCard(
    @StringRes name: Int,
    granted: Boolean,
    target: FixTarget,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (granted) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
            modifier = Modifier.padding(Spacing.Large),
        ) {
            Icon(
                painter = painterResource(if (granted) TelltypeIcons.CheckCircle else TelltypeIcons.Warning),
                contentDescription = null,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(name), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(if (granted) R.string.setup_granted else R.string.setup_not_granted),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!granted) {
                FilledTonalButton(onClick = { onAction(SetupAction.Grant(target)) }) {
                    Text(text = stringResource(R.string.setup_allow))
                }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsHelp(modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = Modifier.padding(Spacing.Large)) {
            Text(
                text = stringResource(R.string.setup_restricted_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            RestrictedSteps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                    Text(
                        text = stringResource(R.string.setup_numbered, index + 1),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(text = stringResource(step), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val RestrictedSteps =
    listOf(R.string.setup_restricted_1, R.string.setup_restricted_2, R.string.setup_restricted_3)

/** Fixed values used in this file. */
private object PracticeDefaults {
    const val PRACTICE_LINES = 4
}
