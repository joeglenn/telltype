package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.R
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/**
 * Asks before something that cannot be undone (delete an entry, clear history), as a bottom sheet inside the
 * app; [extra] holds optional choices shown under the body. Show it while the question is open; both buttons and
 * swiping it away call [onDismiss] or [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet is still experimental in Material 3 1.4.
@Composable
fun ConfirmSheet(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        ConfirmSheetContent(
            title = title,
            body = body,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            destructive = destructive,
            extra = extra,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/**
 * The content of [ConfirmSheet]: a question, its consequence, optional [extra] choices (for example [CheckRow]s that
 * widen or narrow what happens), Cancel and the confirming action.
 */
@Composable
fun ConfirmSheetContent(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = true,
    extra: (@Composable () -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.Medium),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.ExtraLarge, vertical = Spacing.Large),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = body, style = MaterialTheme.typography.bodyMedium)
        extra?.invoke()
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small, androidx.compose.ui.Alignment.End),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Small),
        ) {
            OutlinedButton(onClick = onDismiss) { Text(text = stringResource(R.string.ds_cancel)) }
            Button(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) { Text(text = confirmLabel) }
        }
    }
}

@Preview
@Composable
private fun ConfirmSheetContentPreview() {
    TelltypePreviewTheme {
        ConfirmSheetContent(
            title = "Delete this dictation?",
            body = "It is removed from History on this phone.",
            confirmLabel = "Delete",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
