package net.jbglenn.telltype.ui.screens.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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

/** Diagnostics (docs/PLAN.md section 13). Stateless: draws [state] and reports [DiagnosticsAction]s. */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onAction: (DiagnosticsAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    onNavigateUp: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.diagnostics_title), onNavigateUp = onNavigateUp) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { insets ->
        val permissions = state.permissions
        if (permissions == null) {
            LoadingState(modifier = Modifier.padding(insets))
        } else {
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = Spacing.ExtraLarge),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            ) {
                item(key = "permissions") {
                    PermissionSection(permissions, onAction, Modifier.screenContent(widthClass))
                }
                item(key = "server") { ServerHealthSection(state, onAction, Modifier.screenContent(widthClass)) }
                item(key = "tools") { ToolsSection(state, onAction, Modifier.screenContent(widthClass)) }
                item(key = "speed") { SpeedSection(state.speed, Modifier.screenContent(widthClass)) }
                item(key = "sessions-header") {
                    Column(modifier = Modifier.screenContent(widthClass)) {
                        SectionHeader(text = stringResource(R.string.diagnostics_sessions))
                        if (state.sessions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.diagnostics_sessions_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(state.sessions, key = { it.id.value }) { entry ->
                    ContentListItem(
                        headline = sessionHeadline(entry, state.zone),
                        supporting = sessionSupporting(entry),
                        icon = if (entry.errorCode == null) TelltypeIcons.Speed else TelltypeIcons.Warning,
                        headlineMaxLines = 1,
                        modifier = Modifier.screenContent(widthClass),
                    )
                }
            }
        }
    }
}
