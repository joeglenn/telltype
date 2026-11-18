package net.jbglenn.telltype.ui.screens.snippets

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.AddButton
import net.jbglenn.telltype.ui.designsystem.component.ConfirmSheet
import net.jbglenn.telltype.ui.designsystem.component.ContentListItem
import net.jbglenn.telltype.ui.designsystem.component.EmptyState
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.EditorCard
import net.jbglenn.telltype.ui.screens.common.EditorField
import net.jbglenn.telltype.ui.screens.dictionary.FabClearance

/** Snippets (docs/PLAN.md section 5.3). Stateless: draws [state] and reports [SnippetsAction]s. */
@Composable
fun SnippetsScreen(
    state: SnippetsUiState,
    onAction: (SnippetsAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.snippets_title)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.editor == null && !state.loading) {
                AddButton(label = stringResource(R.string.snippets_add), onClick = { onAction(SnippetsAction.Add) })
            }
        },
        modifier = modifier,
    ) { insets ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(insets))
        } else {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = Spacing.Small, bottom = FabClearance),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            ) {
                state.editor?.let { form ->
                    item(key = "editor") {
                        SnippetEditorCard(
                            form,
                            onAction,
                            Modifier.screenContent(widthClass).padding(bottom = Spacing.Small),
                        )
                    }
                }
                if (state.snippets.isEmpty() && state.editor == null) {
                    item(key = "empty") {
                        EmptyState(
                            icon = TelltypeIcons.Snippet,
                            title = stringResource(R.string.snippets_empty_title),
                            body = stringResource(R.string.snippets_empty_body),
                        )
                    }
                }
                items(state.snippets, key = { it.id.value }) { snippet ->
                    SnippetRow(snippet, onAction, Modifier.screenContent(widthClass))
                }
            }
        }
    }
    state.pendingDelete?.let { snippet ->
        ConfirmSheet(
            title = stringResource(R.string.snippets_delete_title, snippet.trigger),
            body = stringResource(R.string.snippets_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onAction(SnippetsAction.ConfirmDelete) },
            onDismiss = { onAction(SnippetsAction.CancelDelete) },
        )
    }
}

@Composable
private fun SnippetRow(snippet: Snippet, onAction: (SnippetsAction) -> Unit, modifier: Modifier = Modifier) {
    ContentListItem(
        headline = stringResource(R.string.snippets_trigger_quoted, snippet.trigger),
        supporting = snippet.text,
        onClick = { onAction(SnippetsAction.Edit(snippet.id)) },
        trailing = {
            IconButton(onClick = { onAction(SnippetsAction.AskDelete(snippet.id)) }) {
                Icon(
                    painter = painterResource(TelltypeIcons.Delete),
                    contentDescription = stringResource(R.string.snippets_delete_named, snippet.trigger),
                )
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun SnippetEditorCard(
    editor: SnippetEditor,
    onAction: (SnippetsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = editor.error?.let { stringResource(it) }
    EditorCard(
        title = stringResource(if (editor.editing == null) R.string.snippets_add else R.string.snippets_edit),
        error = error,
        onSave = { onAction(SnippetsAction.Save) },
        onCancel = { onAction(SnippetsAction.CancelEdit) },
        modifier = modifier,
    ) {
        EditorField(
            label = stringResource(R.string.snippets_field_trigger),
            supporting = stringResource(R.string.snippets_field_trigger_help),
            value = editor.trigger,
            onValueChange = { onAction(SnippetsAction.TriggerChanged(it)) },
            isError =
                editor.error == R.string.snippets_error_trigger_blank ||
                    editor.error == R.string.snippets_error_duplicate,
        )
        EditorField(
            label = stringResource(R.string.snippets_field_text),
            value = editor.text,
            onValueChange = { onAction(SnippetsAction.TextChanged(it)) },
            singleLine = false,
            isError = editor.error == R.string.snippets_error_text_blank,
        )
    }
}
