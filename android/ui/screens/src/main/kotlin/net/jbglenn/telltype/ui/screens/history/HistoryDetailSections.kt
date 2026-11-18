package net.jbglenn.telltype.ui.screens.history

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import kotlin.time.Duration
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.ui.designsystem.Layout
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.tabularNumbers
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.LabelValueLayout
import net.jbglenn.telltype.ui.screens.common.durationText
import net.jbglenn.telltype.ui.screens.common.label

/** App, category, mode, cleanup, delivery and length of one entry. */
@Composable
internal fun EntryFacts(entry: HistoryEntry, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.history_details))
        FactRow(label = stringResource(R.string.history_fact_app), value = entry.app.name)
        FactRow(label = stringResource(R.string.history_fact_category), value = stringResource(entry.category.label))
        FactRow(label = stringResource(R.string.history_fact_mode), value = stringResource(entry.mode.label))
        FactRow(
            label = stringResource(R.string.history_fact_cleanup),
            value = stringResource(entry.cleanupStatus?.label ?: R.string.history_cleanup_none),
        )
        FactRow(
            label = stringResource(R.string.history_fact_delivered),
            value = stringResource(entry.insertedVia.label),
        )
        FactRow(label = stringResource(R.string.history_fact_length), value = durationText(entry.audioDuration))
    }
}

/** How long each stage took (section 13, session trace), with the server's share from `Server-Timing`. */
@Composable
internal fun TimingsTable(timings: SessionTimings, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.history_timings))
        TimingRow(R.string.timing_recording, timings.recording)
        TimingRow(R.string.timing_finalising, timings.finalising)
        TimingRow(R.string.timing_transcription, timings.transcription)
        TimingRow(R.string.timing_cleanup, timings.cleanup)
        TimingRow(R.string.timing_insertion, timings.insertion)
        TimingRow(R.string.timing_end_to_end, timings.endToEnd, emphasised = true)
        timings.server.forEach { entry ->
            FactRow(label = stringResource(R.string.timing_server, entry.name), value = durationText(entry.duration))
        }
    }
}

@Composable
private fun TimingRow(@StringRes label: Int, duration: Duration?, emphasised: Boolean = false) {
    FactRow(
        label = stringResource(label),
        value = duration?.let { durationText(it) } ?: stringResource(R.string.timing_not_run),
        emphasised = emphasised,
    )
}

/**
 * One label and value pair, read as one item by screen readers: side by side when both fit on a line, otherwise
 * the value under the label (see [LabelValueLayout]).
 */
@Composable
internal fun FactRow(label: String, value: String, modifier: Modifier = Modifier, emphasised: Boolean = false) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        LabelValueLayout(
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            value = {
                val style = if (emphasised) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
                Text(text = value, style = style.tabularNumbers())
            },
            gap = Spacing.Large,
            stackedGap = Spacing.ExtraSmall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MinTouchTarget)
                .padding(vertical = Spacing.Small),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@get:StringRes
private val DictationMode.label: Int
    get() = when (this) {
        DictationMode.DICTATE -> R.string.mode_dictate
        DictationMode.COMMAND -> R.string.mode_command
        DictationMode.SNIPPET -> R.string.mode_snippet
    }

@get:StringRes
private val CleanupStatus.label: Int
    get() = when (this) {
        CleanupStatus.APPLIED -> R.string.cleanup_applied
        CleanupStatus.SKIPPED_GUARD -> R.string.cleanup_skipped_guard
        CleanupStatus.SKIPPED_TIMEOUT -> R.string.cleanup_skipped_timeout
        CleanupStatus.SKIPPED_SHORT -> R.string.cleanup_skipped_short
        CleanupStatus.SKIPPED_ERROR -> R.string.cleanup_skipped_error
        CleanupStatus.OFF -> R.string.cleanup_off
    }

@get:StringRes
private val InsertedVia.label: Int
    get() = when (this) {
        InsertedVia.INPUT_CONNECTION, InsertedVia.SET_TEXT -> R.string.delivered_typed
        InsertedVia.PASTE -> R.string.delivered_pasted
        InsertedVia.HANDOFF -> R.string.delivered_clipboard
        InsertedVia.NONE -> R.string.delivered_none
    }
