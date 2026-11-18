package net.jbglenn.telltype.ui.screens.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.ActionChip
import net.jbglenn.telltype.ui.designsystem.component.EmptyState
import net.jbglenn.telltype.ui.designsystem.component.ErrorBanner
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.designsystem.error.errorMessageForCode
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.Formats
import net.jbglenn.telltype.ui.screens.common.currentLocale

/** One entry: final text, raw transcript, facts, per-stage timings and the actions. */
@Composable
internal fun HistoryDetailPane(
    detail: HistoryDetailState,
    state: HistoryUiState,
    onAction: (HistoryAction) -> Unit,
    widthClass: WidthClass,
    modifier: Modifier = Modifier,
) {
    val entry = detail.entry
    when {
        detail.missing -> EmptyState(
            icon = TelltypeIcons.SearchOff,
            title = stringResource(R.string.history_missing_title),
            body = stringResource(R.string.history_missing_body),
            modifier = modifier,
        )

        entry == null -> LoadingState(modifier = modifier)

        else -> Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Large),
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .screenContent(widthClass)
                .padding(vertical = Spacing.Large),
        ) {
            Text(
                text = Formats.dateTime(entry.createdAt, state.zone, currentLocale()),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (entry.archived) {
                Text(
                    text = stringResource(R.string.history_in_archive),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.errorCode?.let { code ->
                ErrorBanner(message = stringResource(errorMessageForCode(code) ?: R.string.history_failed))
            }
            TextCard(
                title = stringResource(R.string.history_final_text),
                text = entry.finalText.ifBlank { stringResource(R.string.history_no_text) },
            )
            DetailActions(detail = detail, onAction = onAction)
            RerunResult(detail = detail, onAction = onAction)
            TextCard(
                title = stringResource(R.string.history_raw_transcript),
                text = entry.rawTranscript.ifBlank { stringResource(R.string.history_no_text) },
                muted = true,
            )
            if (detail.canReport) {
                ActionChip(
                    label = stringResource(R.string.history_copy_raw),
                    icon = TelltypeIcons.Copy,
                    onClick = { onAction(HistoryAction.CopyRaw) },
                )
            }
            EntryFacts(entry = entry)
            TimingsTable(timings = entry.timings)
        }
    }
}

@Composable
private fun DetailActions(
    detail: HistoryDetailState,
    onAction: (HistoryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        modifier = modifier.fillMaxWidth(),
    ) {
        if (detail.hasFinalText) {
            ActionChip(
                label = stringResource(R.string.history_copy),
                icon = TelltypeIcons.Copy,
                onClick = { onAction(HistoryAction.CopyFinal) },
            )
        }
        if (detail.canRerun) {
            ActionChip(
                label = stringResource(R.string.history_rerun),
                icon = TelltypeIcons.Restart,
                onClick = { onAction(HistoryAction.RerunCleanup) },
            )
        }
        if (detail.canReport) {
            ActionChip(
                label = stringResource(
                    when (detail.reportState) {
                        ReportState.IDLE -> R.string.history_report
                        ReportState.SENDING -> R.string.history_reporting
                        ReportState.SENT -> R.string.history_report_sent
                    },
                ),
                icon = TelltypeIcons.Flag,
                onClick = { onAction(HistoryAction.ReportWrong) },
            )
        }
        detail.entry?.let { entry ->
            ActionChip(
                label = stringResource(if (entry.archived) R.string.history_unarchive else R.string.history_archive),
                icon = if (entry.archived) TelltypeIcons.Unarchive else TelltypeIcons.Archive,
                onClick = {
                    onAction(if (entry.archived) HistoryAction.Unarchive(entry.id) else HistoryAction.Archive(entry.id))
                },
            )
        }
        ActionChip(
            label = stringResource(R.string.history_delete),
            icon = TelltypeIcons.Delete,
            onClick = { onAction(HistoryAction.AskDelete) },
        )
    }
}

@Composable
private fun RerunResult(detail: HistoryDetailState, onAction: (HistoryAction) -> Unit, modifier: Modifier = Modifier) {
    when (val rerun = detail.rerun) {
        RerunState.Idle -> Unit

        RerunState.Running -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = modifier) {
            Text(text = stringResource(R.string.history_rerun_running), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is RerunState.Done -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small), modifier = modifier) {
            TextCard(title = stringResource(R.string.history_rerun_result), text = rerun.text)
            ActionChip(
                label = stringResource(R.string.history_copy_rerun),
                icon = TelltypeIcons.Copy,
                onClick = { onAction(HistoryAction.CopyRerun) },
            )
        }

        is RerunState.Failed -> ErrorBanner(
            message = stringResource(rerun.message),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { onAction(HistoryAction.RerunCleanup) },
            modifier = modifier,
        )
    }
}

/** A titled block of selectable text. */
@Composable
internal fun TextCard(title: String, text: String, modifier: Modifier = Modifier, muted: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (muted) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.Large), verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            SelectionContainer {
                Text(text = text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
