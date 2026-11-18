package net.jbglenn.telltype.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.ui.designsystem.component.CheckRow
import net.jbglenn.telltype.ui.designsystem.component.ConfirmSheet
import net.jbglenn.telltype.ui.screens.R

/**
 * "Clear history?": says how many dictations go and that it cannot be undone, and asks whether the Archive goes
 * too (not by default, D52). History and Privacy settings.
 *
 * @param historyCount entries in History, not archived.
 * @param archivedCount entries in the Archive.
 * @param includeArchive the Archive is deleted too.
 */
@Composable
internal fun ClearHistorySheet(
    historyCount: Int,
    archivedCount: Int,
    includeArchive: Boolean,
    onIncludeArchiveChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val going = historyCount + if (includeArchive) archivedCount else 0
    ConfirmSheet(
        title = stringResource(R.string.history_clear_title),
        body = pluralStringResource(R.plurals.history_clear_body, going, going),
        confirmLabel = stringResource(R.string.history_clear),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        extra = if (archivedCount > 0) {
            {
                CheckRow(
                    title = pluralStringResource(R.plurals.history_clear_include_archive, archivedCount, archivedCount),
                    checked = includeArchive,
                    onCheckedChange = onIncludeArchiveChange,
                )
            }
        } else {
            null
        },
    )
}
