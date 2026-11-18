package net.jbglenn.telltype.ui.screens.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.EmptyState
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.screens.R

/** The search field and the list (History or the Archive), or the loading or empty state that stands in for it. */
@Composable
internal fun HistoryListPane(
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    widthClass: WidthClass,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (state.archive || !state.historyOff) {
            SearchField(
                label = stringResource(if (state.archive) R.string.history_search_archive else R.string.history_search),
                query = state.query,
                onQueryChange = { onAction(HistoryAction.QueryChanged(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = widthClass.margin, vertical = Spacing.Small),
            )
        }
        val entries = state.entries
        when {
            state.historyOff && !state.archive -> EmptyState(
                icon = TelltypeIcons.History,
                title = stringResource(R.string.history_off_title),
                body = stringResource(R.string.history_off_body),
                actionLabel = stringResource(R.string.history_off_action),
                onAction = { onAction(HistoryAction.OpenSettings) },
            )

            entries == null -> LoadingState()

            entries.isEmpty() && state.query.isNotBlank() -> EmptyState(
                icon = TelltypeIcons.SearchOff,
                title = stringResource(R.string.history_no_results_title),
                body = stringResource(R.string.history_no_results_body),
            )

            entries.isEmpty() && state.archive -> EmptyState(
                icon = TelltypeIcons.Archive,
                title = stringResource(R.string.history_archive_empty_title),
                body = stringResource(R.string.history_archive_empty_body),
            )

            entries.isEmpty() -> EmptyState(
                icon = TelltypeIcons.History,
                title = stringResource(R.string.history_empty_title),
                body = stringResource(R.string.history_empty_body),
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = Spacing.ExtraLarge),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id.value }) { entry ->
                    HistoryRow(entry = entry, state = state, widthClass = widthClass, onAction = onAction)
                }
            }
        }
    }
}

@Composable
private fun SearchField(label: String, query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text(text = label) },
        leadingIcon = { Icon(painter = painterResource(TelltypeIcons.Search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        painter = painterResource(TelltypeIcons.Close),
                        contentDescription = stringResource(R.string.history_search_clear),
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier,
    )
}
