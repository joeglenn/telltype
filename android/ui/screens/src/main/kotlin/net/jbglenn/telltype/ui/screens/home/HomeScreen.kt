package net.jbglenn.telltype.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.ContentListItem
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.history.entryHeadline
import net.jbglenn.telltype.ui.screens.history.entrySupporting

/** Home (docs/PLAN.md section 5.3). Stateless: draws [state] and reports [HomeAction]s. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.app_name)) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { insets ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(insets))
        } else {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = Spacing.ExtraLarge),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            ) {
                item(key = "status") {
                    HomeStatusCard(
                        state = state,
                        onAction = onAction,
                        modifier = Modifier
                            .screenContent(widthClass)
                            .padding(top = Spacing.Small),
                    )
                }
                item(key = "bubble") { BubbleSection(state, onAction, Modifier.screenContent(widthClass)) }
                item(key = "today") { TodaySection(state.stats, Modifier.screenContent(widthClass)) }
                recentSection(state, onAction, widthClass)
            }
        }
    }
}

private fun LazyListScope.recentSection(state: HomeUiState, onAction: (HomeAction) -> Unit, widthClass: WidthClass) {
    item(key = "recent-header") {
        SectionHeader(text = stringResource(R.string.home_recent), modifier = Modifier.screenContent(widthClass))
    }
    if (state.recent.isEmpty()) {
        item(key = "recent-empty") {
            Text(
                text = stringResource(R.string.home_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.screenContent(widthClass),
            )
        }
    } else {
        items(state.recent, key = { it.id.value }) { entry ->
            ContentListItem(
                headline = entryHeadline(entry),
                supporting = entrySupporting(entry, state.zone),
                icon = TelltypeIcons.History,
                onClick = { onAction(HomeAction.OpenEntry(entry.id)) },
                modifier = Modifier.screenContent(widthClass),
            )
        }
        item(key = "recent-all") {
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.screenContent(widthClass),
            ) {
                TextButton(onClick = { onAction(HomeAction.OpenHistory) }) {
                    Text(text = stringResource(R.string.home_see_all))
                }
            }
        }
    }
}
