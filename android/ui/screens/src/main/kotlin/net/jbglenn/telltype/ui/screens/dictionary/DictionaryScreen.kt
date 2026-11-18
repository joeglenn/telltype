package net.jbglenn.telltype.ui.screens.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.AddButton
import net.jbglenn.telltype.ui.designsystem.component.ConfirmSheet
import net.jbglenn.telltype.ui.designsystem.component.EmptyState
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R

/** The Dictionary (docs/PLAN.md section 5.3). Stateless: draws [state] and reports [DictionaryAction]s. */
@Composable
fun DictionaryScreen(
    state: DictionaryUiState,
    onAction: (DictionaryAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.dictionary_title)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (state.editor == null && !state.loading) {
                val label = if (state.tab ==
                    DictionaryTab.WORDS
                ) {
                    R.string.dictionary_add_word
                } else {
                    R.string.dictionary_add_pair
                }
                AddButton(label = stringResource(label), onClick = { onAction(DictionaryAction.Add) })
            }
        },
        modifier = modifier,
    ) { insets ->
        Column(modifier = Modifier.padding(insets)) {
            DictionaryTabs(state = state, onAction = onAction)
            if (state.loading) {
                LoadingState()
            } else {
                DictionaryList(state = state, onAction = onAction, widthClass = widthClass)
            }
        }
    }
    state.pendingDelete?.let { target ->
        ConfirmSheet(
            title = stringResource(R.string.dictionary_delete_title, target.label),
            body = stringResource(R.string.dictionary_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { onAction(DictionaryAction.ConfirmDelete) },
            onDismiss = { onAction(DictionaryAction.CancelDelete) },
        )
    }
}

@Composable
private fun DictionaryTabs(state: DictionaryUiState, onAction: (DictionaryAction) -> Unit) {
    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
        Tab(
            selected = state.tab == DictionaryTab.WORDS,
            onClick = { onAction(DictionaryAction.SelectTab(DictionaryTab.WORDS)) },
            text = {
                Text(
                    text = pluralStringResource(
                        R.plurals.dictionary_tab_words,
                        state.terms.size,
                        state.terms.size,
                    ),
                )
            },
        )
        Tab(
            selected = state.tab == DictionaryTab.CORRECTIONS,
            onClick = { onAction(DictionaryAction.SelectTab(DictionaryTab.CORRECTIONS)) },
            text = {
                Text(
                    text = pluralStringResource(
                        R.plurals.dictionary_tab_corrections,
                        state.replacements.size,
                        state.replacements.size,
                    ),
                )
            },
        )
    }
}

@Composable
private fun DictionaryList(state: DictionaryUiState, onAction: (DictionaryAction) -> Unit, widthClass: WidthClass) {
    LazyColumn(
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = Spacing.Small, bottom = FabClearance),
        modifier = Modifier.fillMaxSize(),
    ) {
        state.editor?.let { form ->
            item(key = "editor") {
                DictionaryEditorCard(
                    editor = form,
                    onAction = onAction,
                    modifier = Modifier
                        .screenContent(widthClass)
                        .padding(bottom = Spacing.Small),
                )
            }
        }
        when (state.tab) {
            DictionaryTab.WORDS -> wordItems(state, onAction, widthClass)
            DictionaryTab.CORRECTIONS -> correctionItems(state, onAction, widthClass)
        }
    }
}

private fun LazyListScope.wordItems(
    state: DictionaryUiState,
    onAction: (DictionaryAction) -> Unit,
    widthClass: WidthClass,
) {
    if (state.terms.isEmpty() && state.editor == null) {
        item(key = "empty") {
            EmptyState(
                icon = TelltypeIcons.Dictionary,
                title = stringResource(R.string.dictionary_words_empty_title),
                body = stringResource(R.string.dictionary_words_empty_body),
            )
        }
    }
    items(state.terms, key = { "term-${it.id.value}" }) { term ->
        WordRow(term = term, onAction = onAction, modifier = Modifier.screenContent(widthClass))
    }
}

private fun LazyListScope.correctionItems(
    state: DictionaryUiState,
    onAction: (DictionaryAction) -> Unit,
    widthClass: WidthClass,
) {
    if (state.replacements.isEmpty() && state.editor == null) {
        item(key = "empty") {
            EmptyState(
                icon = TelltypeIcons.Dictionary,
                title = stringResource(R.string.dictionary_pairs_empty_title),
                body = stringResource(R.string.dictionary_pairs_empty_body),
            )
        }
    }
    items(state.replacements, key = { "pair-${it.id.value}" }) { pair ->
        CorrectionRow(replacement = pair, onAction = onAction, modifier = Modifier.screenContent(widthClass))
    }
}

/** Room below the list so the last row is not hidden behind the floating button. */
internal val FabClearance = 96.dp
