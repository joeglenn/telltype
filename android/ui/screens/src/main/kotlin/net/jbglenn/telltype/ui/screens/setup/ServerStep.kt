package net.jbglenn.telltype.ui.screens.setup

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import net.jbglenn.telltype.domain.settings.ModelChoices
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.ErrorBanner
import net.jbglenn.telltype.ui.designsystem.component.StatusCard
import net.jbglenn.telltype.ui.designsystem.component.StatusTone
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.formatFingerprint

/**
 * Step 1 (D60): the server address, the optional key, under Advanced the optional text model address, Test, and the
 * fingerprint to confirm when the system does not trust a certificate. Autofill is kept away from it ([NoAutofill]).
 */
@Composable
internal fun ServerStep(state: SetupUiState, onAction: (SetupAction) -> Unit, modifier: Modifier = Modifier) {
    NoAutofill()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = modifier) {
        Text(text = stringResource(R.string.setup_server_body), style = MaterialTheme.typography.bodyMedium)
        AddressField(
            value = state.address,
            error = state.addressError,
            labels = AddressLabels(R.string.setup_address_label, R.string.setup_address_supporting),
            onValueChange = { onAction(SetupAction.AddressChanged(it)) },
        )
        OutlinedTextField(
            value = state.key,
            onValueChange = { onAction(SetupAction.KeyChanged(it)) },
            label = { Text(text = stringResource(R.string.setup_key)) },
            supportingText = if (state.keySaved) {
                { Text(text = stringResource(R.string.setup_key_saved)) }
            } else {
                null
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            // The key is not a login: autofill services must neither fill it nor offer to save it ([NoAutofill]
            // excludes the whole form as well).
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDataType = ContentDataType.None },
        )
        TextButton(onClick = { onAction(SetupAction.ToggleAdvanced) }) {
            Text(
                text = stringResource(
                    if (state.advancedOpen) R.string.setup_advanced_hide else R.string.setup_advanced_show,
                ),
            )
        }
        if (state.advancedOpen) {
            AddressField(
                value = state.textAddress,
                error = state.textAddressError,
                labels = AddressLabels(R.string.setup_text_address_label, R.string.setup_text_address_supporting),
                onValueChange = { onAction(SetupAction.TextAddressChanged(it)) },
            )
        }
        state.formError?.let { ErrorBanner(message = stringResource(it)) }
        Button(
            onClick = { onAction(SetupAction.Test) },
            enabled = state.connection != ConnectionState.Checking,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(text = stringResource(R.string.setup_test)) }
        ConnectionResult(connection = state.connection, onAction = onAction)
    }
}

/**
 * The label and help of an address field.
 *
 * @property label the field's label.
 * @property supporting the help under it, replaced by the problem after Test.
 */
private class AddressLabels(@param:StringRes val label: Int, @param:StringRes val supporting: Int)

/** An address field: IP address or host name, with `http://` or `https://` when the server needs it. */
@Composable
private fun AddressField(
    value: String,
    @StringRes error: Int?,
    labels: AddressLabels,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = stringResource(labels.label)) },
        placeholder = { Text(text = stringResource(R.string.setup_address_placeholder)) },
        supportingText = { Text(text = stringResource(error ?: labels.supporting)) },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectionResult(
    connection: ConnectionState,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (connection) {
        ConnectionState.Idle -> Unit

        ConnectionState.Saved -> StatusCard(
            title = stringResource(R.string.setup_saved_title),
            body = stringResource(R.string.setup_saved_body),
            tone = StatusTone.GOOD,
            modifier = modifier,
        )

        ConnectionState.Checking -> Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(text = stringResource(R.string.setup_checking), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is ConnectionState.ConfirmFingerprint -> FingerprintCard(connection, onAction, modifier)

        is ConnectionState.Connected -> StatusCard(
            title = stringResource(R.string.setup_connected_title),
            body = connectedText(connection.summary),
            tone = StatusTone.GOOD,
            modifier = modifier,
        )

        is ConnectionState.Failed -> ErrorBanner(
            message = stringResource(connection.message),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(SetupAction.Test) },
            modifier = modifier,
        )
    }
}

/** The certificate to compare, in large monospace groups, with the two answers. */
@Composable
private fun FingerprintCard(
    connection: ConnectionState.ConfirmFingerprint,
    onAction: (SetupAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
            modifier = Modifier.fillMaxWidth().padding(Spacing.Large),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                Icon(painter = painterResource(TelltypeIcons.VerifiedUser), contentDescription = null)
                Text(
                    text = stringResource(R.string.setup_fingerprint_title),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (connection.changed) {
                ErrorBanner(message = stringResource(R.string.setup_fingerprint_changed))
            }
            if (connection.address.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.setup_fingerprint_address, connection.address),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(text = stringResource(R.string.setup_fingerprint_body), style = MaterialTheme.typography.bodyMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
                modifier = Modifier.semantics(mergeDescendants = true) {},
            ) {
                val groups = formatFingerprint(connection.fingerprint, bytesPerLine = FingerprintDefaults.GROUP_BYTES)
                groups.lines().forEach { group ->
                    Text(
                        text = group,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
                verticalArrangement = Arrangement.spacedBy(Spacing.Small),
            ) {
                Button(onClick = { onAction(SetupAction.ConfirmFingerprint) }) {
                    Text(text = stringResource(R.string.setup_fingerprint_match))
                }
                OutlinedButton(onClick = { onAction(SetupAction.RejectFingerprint) }) {
                    Text(text = stringResource(R.string.setup_fingerprint_no_match))
                }
            }
        }
    }
}

/** Which addresses answered, how each was checked, and which models were chosen (D60). */
@Composable
private fun connectedText(summary: ConnectedSummary): String {
    val default = stringResource(R.string.setup_model_server_default)
    val lines = listOfNotNull(
        summary.gateVersion?.let { stringResource(R.string.setup_connected_version, it) },
        stringResource(
            R.string.setup_connected_speech,
            summary.speechAddress,
            stringResource(summary.speechCheck.labelRes()),
        ),
        stringResource(
            R.string.setup_connected_text,
            summary.textAddress ?: stringResource(R.string.setup_same_address),
            stringResource(summary.textCheck.labelRes()),
        ),
        stringResource(R.string.setup_connected_cleanup_loading).takeUnless { summary.cleanupReady },
        stringResource(
            R.string.setup_connected_models,
            ModelChoices.displayName(summary.speechModel),
            summary.cleanupModel?.let(ModelChoices::displayName) ?: default,
            summary.commandModel?.let(ModelChoices::displayName) ?: default,
        ),
    )
    return lines.joinToString("\n")
}

/** Fixed values used in this file. */
private object FingerprintDefaults {
    /** Bytes per group; groups wrap to the width, so large text never splits one. */
    const val GROUP_BYTES = 4
}
