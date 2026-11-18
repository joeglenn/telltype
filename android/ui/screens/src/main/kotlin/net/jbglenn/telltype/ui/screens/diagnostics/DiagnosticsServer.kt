package net.jbglenn.telltype.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.settings.BackendHealth
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.component.ErrorBanner
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.error.connectionMessageRes
import net.jbglenn.telltype.ui.designsystem.error.errorMessageForCode
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.Formats
import net.jbglenn.telltype.ui.screens.common.currentLocale
import net.jbglenn.telltype.ui.screens.common.durationText
import net.jbglenn.telltype.ui.screens.common.label
import net.jbglenn.telltype.ui.screens.history.FactRow

/** The server: its addresses, status, the models chosen (and whether still offered), the round trip, Check now. */
@Composable
internal fun ServerHealthSection(
    state: DiagnosticsUiState,
    onAction: (DiagnosticsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.diagnostics_server))
        when (val reach = state.reachability) {
            ServerReachability.Unknown -> Text(
                text = stringResource(R.string.diagnostics_server_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is ServerReachability.Unreachable -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                ErrorBanner(message = stringResource(reach.error.connectionMessageRes()))
                FactRow(
                    label = stringResource(R.string.diagnostics_checked),
                    value = Formats.time(reach.checkedAt, state.zone, currentLocale()),
                )
            }

            is ServerReachability.Reachable -> ReachableServer(reach, state)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.Small),
        ) {
            FilledTonalButton(onClick = { onAction(DiagnosticsAction.CheckServer) }, enabled = !state.checkingServer) {
                Text(text = stringResource(R.string.diagnostics_check_now))
            }
            if (state.checkingServer) {
                CircularProgressIndicator(modifier = Modifier.padding(start = Spacing.Medium).size(24.dp))
            }
        }
    }
}

/** What the last probe found: addresses, status, models, round trip, when. */
@Composable
private fun ReachableServer(reach: ServerReachability.Reachable, state: DiagnosticsUiState) {
    val models = state.models
    Column {
        FactRow(label = stringResource(R.string.diagnostics_address), value = reach.endpoint.baseUrl)
        reach.textEndpoint?.let {
            FactRow(label = stringResource(R.string.diagnostics_text_address), value = it.baseUrl)
        }
        FactRow(label = stringResource(R.string.diagnostics_status), value = statusText(reach.health))
        ModelRow(
            R.string.diagnostics_model_speech,
            models.speech,
            isMissing(models.speech, reach.offered.speech),
            reach.health?.stt,
        )
        ModelRow(
            R.string.diagnostics_model_cleanup,
            models.cleanup,
            isMissing(models.cleanup, reach.offered.text),
            reach.health?.cleanup,
        )
        ModelRow(
            R.string.diagnostics_model_command,
            models.command,
            isMissing(models.command, reach.offered.text),
            reach.health?.assistant,
        )
        FactRow(label = stringResource(R.string.diagnostics_round_trip), value = durationText(reach.roundTrip))
        FactRow(
            label = stringResource(R.string.diagnostics_checked),
            value = Formats.time(reach.checkedAt, state.zone, currentLocale()),
        )
    }
}

/** A chosen model that the address's list (when it has one) no longer contains. */
private fun isMissing(chosen: String?, offered: List<OfferedModel>?): Boolean =
    chosen != null && offered != null && offered.none { it.id == chosen }

/** Our gate's overall status, or that the server answers without one (a stock server). */
@Composable
private fun statusText(health: ServerHealth?): String = when {
    health == null -> stringResource(R.string.diagnostics_status_answering)
    health.status == ServerStatus.OK -> stringResource(R.string.diagnostics_status_ok, health.version)
    else -> stringResource(R.string.diagnostics_status_degraded, health.version)
}

/**
 * One model: the chosen name (none chosen: our gate's own model from its health, `whisper-1` for speech, else the
 * server's default), whether our gate reports it loaded, and a warning when the server's list no longer has it (D60).
 */
@Composable
private fun ModelRow(label: Int, chosen: String?, missing: Boolean, backend: BackendHealth?) {
    val fallback = if (label == R.string.diagnostics_model_speech) ServerModels.DEFAULT_SPEECH else null
    val name = chosen ?: backend?.model ?: fallback ?: stringResource(R.string.diagnostics_model_default)
    val value = when {
        missing -> stringResource(R.string.diagnostics_model_missing, name)
        backend == null -> name
        backend.ready -> stringResource(R.string.diagnostics_model_ready, name)
        else -> stringResource(R.string.diagnostics_model_loading, name)
    }
    FactRow(label = stringResource(label), value = value)
}

/** "09:12 · Personal chat": when and where, never what was said. */
@Composable
@ReadOnlyComposable
internal fun sessionHeadline(entry: HistoryEntry, zone: ZoneId): String = stringResource(
    R.string.diagnostics_session_headline,
    Formats.time(entry.createdAt, zone, currentLocale()),
    stringResource(entry.category.label),
)

/** The stage timings of one session, or the problem that ended it. */
@Composable
@ReadOnlyComposable
internal fun sessionSupporting(entry: HistoryEntry): String {
    val code = entry.errorCode
    if (code != null) return stringResource(errorMessageForCode(code) ?: R.string.history_failed)
    val timings = entry.timings
    val parts = listOfNotNull(
        timings.endToEnd?.let { stringResource(R.string.diagnostics_part_total, durationText(it)) },
        timings.transcription?.let { stringResource(R.string.diagnostics_part_transcription, durationText(it)) },
        timings.cleanup?.let { stringResource(R.string.diagnostics_part_cleanup, durationText(it)) },
        timings.insertion?.let { stringResource(R.string.diagnostics_part_insertion, durationText(it)) },
    )
    return if (parts.isEmpty()) {
        stringResource(
            R.string.diagnostics_no_timings,
        )
    } else {
        parts.joinToString(stringResource(R.string.list_separator))
    }
}
