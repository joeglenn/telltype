package net.jbglenn.telltype.ui.screens.history

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.ui.designsystem.component.CheckRow
import net.jbglenn.telltype.ui.designsystem.component.ConfirmSheet
import net.jbglenn.telltype.ui.screens.R

/**
 * Asked when "Save history" is switched off while something is saved (D53): nothing new is saved, and the saved
 * dictations can be deleted at the same time, the Archive listed on its own. Nothing is deleted unless ticked.
 * Deleting the Archive deletes History too, so its tick needs the first one when History holds something.
 *
 * @param historyCount saved entries outside the Archive.
 * @param archivedCount entries in the Archive.
 */
@Composable
internal fun HistoryOffSheet(
    historyCount: Int,
    archivedCount: Int,
    deleteSaved: Boolean,
    deleteArchive: Boolean,
    onDeleteSavedChange: (Boolean) -> Unit,
    onDeleteArchiveChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmSheet(
        title = stringResource(R.string.settings_history_off_title),
        body = stringResource(R.string.settings_history_off_body),
        confirmLabel = stringResource(R.string.settings_history_off_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = deleteSaved || deleteArchive,
        extra = {
            Column {
                if (historyCount > 0) {
                    CheckRow(
                        title = pluralStringResource(R.plurals.settings_history_off_delete, historyCount, historyCount),
                        checked = deleteSaved,
                        onCheckedChange = onDeleteSavedChange,
                    )
                }
                if (archivedCount > 0) {
                    CheckRow(
                        title = pluralStringResource(
                            R.plurals.settings_history_off_delete_archive,
                            archivedCount,
                            archivedCount,
                        ),
                        checked = deleteArchive,
                        onCheckedChange = onDeleteArchiveChange,
                        enabled = deleteSaved || historyCount == 0,
                    )
                }
            }
        },
    )
}
