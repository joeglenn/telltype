package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.R
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/**
 * An inline error at the top of a screen (a save failed, the server cannot be reached), with an optional action such
 * as Retry and an optional dismiss button. Announced politely when it appears.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = Spacing.Large, top = Spacing.Small, bottom = Spacing.Small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(TelltypeIcons.Error), contentDescription = null)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Spacing.Medium)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(TelltypeIcons.Close),
                            contentDescription = stringResource(R.string.ds_dismiss),
                        )
                    }
                }
            }
            if (actionLabel != null && onAction != null) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = Spacing.Small),
                ) {
                    TextButton(onClick = onAction) { Text(text = actionLabel) }
                }
            }
        }
    }
}

@Preview
@Composable
private fun ErrorBannerPreview() {
    TelltypePreviewTheme {
        ErrorBanner(message = "Couldn't save. Try again.", actionLabel = "Retry", onAction = {}, onDismiss = {})
    }
}
