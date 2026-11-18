package net.jbglenn.telltype.ui.screens.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.ui.designsystem.Layout
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.ConfirmSheet
import net.jbglenn.telltype.ui.designsystem.component.EmptyState
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.screens.R

/**
 * History (docs/PLAN.md section 5.3). On phones the list only (an entry opens [HistoryDetailScreen]); from 840 dp
 * the list and the open entry side by side, and Back closes the entry first. The top bar's menu opens the Archive
 * (D52), which replaces the list until Back.
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val twoPanes = widthClass.showsTwoPanes
    BackHandler(enabled = state.archive) { onAction(HistoryAction.CloseArchive) }
    BackHandler(enabled = twoPanes && state.detail != null) { onAction(HistoryAction.CloseDetail) }
    UndoBar(undo = state.undo, hostState = snackbarHostState, onAction = onAction)
    Scaffold(
        topBar = { HistoryTopBar(state = state, onAction = onAction) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { insets ->
        if (twoPanes) {
            Row(modifier = Modifier.padding(insets).fillMaxSize()) {
                HistoryListPane(
                    state = state,
                    onAction = onAction,
                    widthClass = WidthClass.COMPACT,
                    modifier = Modifier
                        .width(Layout.ListPaneWidth)
                        .fillMaxHeight(),
                )
                VerticalDivider()
                Box(modifier = Modifier.weight(1f)) {
                    val detail = state.detail
                    if (detail == null) {
                        EmptyState(
                            icon = TelltypeIcons.History,
                            title = stringResource(R.string.history_pick_title),
                            body = stringResource(R.string.history_pick_body),
                        )
                    } else {
                        HistoryDetailPane(detail = detail, state = state, onAction = onAction, widthClass = widthClass)
                    }
                }
            }
        } else {
            HistoryListPane(
                state = state,
                onAction = onAction,
                widthClass = widthClass,
                modifier = Modifier.padding(insets),
            )
        }
    }
    DeleteConfirmation(state = state, onAction = onAction)
    if (state.confirmingClear) {
        ClearHistorySheet(
            historyCount = state.historyCount,
            archivedCount = state.archivedCount,
            includeArchive = state.clearIncludesArchive,
            onIncludeArchiveChange = { onAction(HistoryAction.ClearIncludesArchive(it)) },
            onConfirm = { onAction(HistoryAction.ConfirmClear) },
            onDismiss = { onAction(HistoryAction.CancelClear) },
        )
    }
}

/** History's title with its menu, or the Archive's with Back. */
@Composable
private fun HistoryTopBar(state: HistoryUiState, onAction: (HistoryAction) -> Unit) {
    if (state.archive) {
        TopBar(
            title = stringResource(R.string.history_archive_title),
            onNavigateUp = { onAction(HistoryAction.CloseArchive) },
        )
    } else {
        TopBar(title = stringResource(R.string.history_title)) { HistoryMenu(state = state, onAction = onAction) }
    }
}

/**
 * The Undo bar of the last swipe (D52): shown while [undo] is set, which the ViewModel clears when the window ends.
 * Undo puts the entry back; swiping the bar away makes the swipe final at once.
 */
@Composable
private fun UndoBar(undo: HistoryUndo?, hostState: SnackbarHostState, onAction: (HistoryAction) -> Unit) {
    if (undo == null) return
    val message = stringResource(undo.kind.message)
    val undoLabel = stringResource(R.string.history_undo)
    val currentOnAction by rememberUpdatedState(onAction)
    LaunchedEffect(undo) {
        val result = hostState.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        currentOnAction(
            if (result == SnackbarResult.ActionPerformed) HistoryAction.Undo else HistoryAction.UndoDismissed,
        )
    }
}

/** The top bar's menu: the Archive with its count and "Clear history", offered while History holds an entry. */
@Composable
private fun HistoryMenu(state: HistoryUiState, onAction: (HistoryAction) -> Unit) {
    if (state.total == 0) return
    Box {
        IconButton(onClick = { onAction(HistoryAction.OpenMenu) }) {
            Icon(
                painter = painterResource(TelltypeIcons.MoreVert),
                contentDescription = stringResource(R.string.history_more),
            )
        }
        DropdownMenu(expanded = state.menuOpen, onDismissRequest = { onAction(HistoryAction.CloseMenu) }) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.history_archive_menu, state.archivedCount)) },
                leadingIcon = { Icon(painter = painterResource(TelltypeIcons.Archive), contentDescription = null) },
                onClick = { onAction(HistoryAction.OpenArchive) },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.history_clear)) },
                leadingIcon = { Icon(painter = painterResource(TelltypeIcons.Delete), contentDescription = null) },
                onClick = { onAction(HistoryAction.AskClear) },
            )
        }
    }
}

/** One entry on its own page (phones), with Back in the top bar. */
@Composable
fun HistoryDetailScreen(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.history_detail_title), onNavigateUp = onNavigateUp) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { insets ->
        UndoBar(undo = state.undo, hostState = snackbarHostState, onAction = onAction)
        state.detail?.let { detail ->
            HistoryDetailPane(
                detail = detail,
                state = state,
                onAction = onAction,
                widthClass = widthClass,
                modifier = Modifier.padding(insets),
            )
        }
    }
    DeleteConfirmation(state = state, onAction = onAction)
}

@Composable
private fun DeleteConfirmation(state: HistoryUiState, onAction: (HistoryAction) -> Unit) {
    if (state.detail?.confirmingDelete == true) {
        ConfirmSheet(
            title = stringResource(R.string.history_delete_title),
            body = stringResource(R.string.history_delete_body),
            confirmLabel = stringResource(R.string.history_delete),
            onConfirm = { onAction(HistoryAction.ConfirmDelete) },
            onDismiss = { onAction(HistoryAction.CancelDelete) },
        )
    }
}
