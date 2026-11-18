package net.jbglenn.telltype.ui.screens.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import java.time.ZoneId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.ui.designsystem.error.errorMessageForCode
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.Formats
import net.jbglenn.telltype.ui.screens.common.currentLocale
import net.jbglenn.telltype.ui.screens.common.durationText
import net.jbglenn.telltype.ui.screens.common.label

/** The headline of an entry in a list: its final text, or what went wrong when there is none. */
@Composable
@ReadOnlyComposable
internal fun entryHeadline(entry: HistoryEntry): String = when {
    entry.finalText.isNotBlank() -> entry.finalText
    entry.errorCode != null -> stringResource(errorMessageForCode(entry.errorCode!!) ?: R.string.history_failed)
    else -> stringResource(R.string.history_no_text)
}

/** "Personal chat · Nov 17, 9:12 AM · 3.0 s", plus "Failed" for an entry that ended in a problem. */
@Composable
@ReadOnlyComposable
internal fun entrySupporting(entry: HistoryEntry, zone: ZoneId): String {
    val parts = listOf(
        stringResource(entry.category.label),
        Formats.dayAndTime(entry.createdAt, zone, currentLocale()),
        durationText(entry.audioDuration),
    )
    val joined = parts.joinToString(separator = stringResource(R.string.list_separator))
    return if (entry.errorCode != null) stringResource(R.string.history_failed_suffix, joined) else joined
}
