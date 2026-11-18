package net.jbglenn.telltype.ui.screens.history

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.ContentListItem
import net.jbglenn.telltype.ui.screens.R

/**
 * One entry of History or the Archive (D52): swipe right to archive (in the Archive: back to History), swipe left
 * to delete; a swipe that is not completed springs back. The same two actions are custom accessibility actions,
 * so swiping is never the only way.
 */
@Composable
internal fun HistoryRow(
    entry: HistoryEntry,
    state: HistoryUiState,
    widthClass: WidthClass,
    onAction: (HistoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keep = if (state.archive) HistoryAction.Unarchive(entry.id) else HistoryAction.Archive(entry.id)
    val keepLabel = stringResource(if (state.archive) R.string.history_unarchive else R.string.history_archive)
    val deleteLabel = stringResource(R.string.history_delete)
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            SwipeBackground(
                direction = swipe.dismissDirection,
                keep = SwipeLook(
                    label = keepLabel,
                    icon = if (state.archive) TelltypeIcons.Unarchive else TelltypeIcons.Archive,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    content = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
                delete = SwipeLook(
                    label = deleteLabel,
                    icon = TelltypeIcons.Delete,
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                ),
            )
        },
        onDismiss = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onAction(keep)
                SwipeToDismissBoxValue.EndToStart -> onAction(HistoryAction.Delete(entry.id))
                SwipeToDismissBoxValue.Settled -> Unit
            }
        },
        modifier = modifier,
    ) {
        ContentListItem(
            headline = entryHeadline(entry),
            supporting = entrySupporting(entry, state.zone),
            icon = if (entry.errorCode == null) TelltypeIcons.History else TelltypeIcons.Warning,
            selected = widthClass.showsTwoPanes && state.detail?.id == entry.id,
            onClick = { onAction(HistoryAction.Select(entry.id)) },
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = widthClass.margin - Spacing.Large)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(keepLabel) {
                            onAction(keep)
                            true
                        },
                        CustomAccessibilityAction(deleteLabel) {
                            onAction(HistoryAction.Delete(entry.id))
                            true
                        },
                    )
                },
        )
    }
}

/**
 * How one swipe direction looks behind the row.
 *
 * @property label what the swipe does.
 * @property icon its icon.
 * @property container the background colour.
 * @property content the colour of the icon and label.
 */
private data class SwipeLook(
    val label: String,
    @param:DrawableRes val icon: Int,
    val container: Color,
    val content: Color,
)

/** What shows behind a row being swiped: the action it will take, on the side the row uncovers. */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, keep: SwipeLook, delete: SwipeLook) {
    val look = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> keep
        SwipeToDismissBoxValue.EndToStart -> delete
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        },
        modifier = Modifier
            .fillMaxSize()
            .background(look.container)
            .padding(horizontal = Spacing.ExtraLarge),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            Icon(painter = painterResource(look.icon), contentDescription = null, tint = look.content)
            Text(text = look.label, color = look.content, style = MaterialTheme.typography.labelLarge)
        }
    }
}
