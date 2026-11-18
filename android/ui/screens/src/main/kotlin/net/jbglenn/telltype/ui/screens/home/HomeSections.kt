package net.jbglenn.telltype.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.component.StatusCard
import net.jbglenn.telltype.ui.designsystem.component.StatusProblemRow
import net.jbglenn.telltype.ui.designsystem.component.StatusTone
import net.jbglenn.telltype.ui.designsystem.component.SwitchRow
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.Formats
import net.jbglenn.telltype.ui.screens.common.currentLocale

/**
 * The list of problems each with its button; or, with none, the snoozed bubble with Resume; or Ready.
 */
@Composable
internal fun HomeStatusCard(state: HomeUiState, onAction: (HomeAction) -> Unit, modifier: Modifier = Modifier) {
    val snoozedUntil = state.snoozedUntil.takeIf { state.bubbleEnabled }
    if (state.problems.isEmpty() && snoozedUntil != null) {
        StatusCard(
            title = stringResource(
                R.string.home_snoozed_title,
                Formats.time(snoozedUntil, state.zone, currentLocale()),
            ),
            body = stringResource(R.string.home_snoozed_body),
            tone = StatusTone.PAUSED,
            modifier = modifier,
        ) {
            FilledTonalButton(onClick = { onAction(HomeAction.EndSnooze) }) {
                Text(text = stringResource(R.string.home_snooze_end))
            }
        }
    } else if (state.problems.isEmpty()) {
        StatusCard(
            title = stringResource(R.string.home_ready_title),
            body = stringResource(if (state.checkingServer) R.string.home_checking else R.string.home_ready_body),
            tone = StatusTone.GOOD,
            modifier = modifier,
        )
    } else {
        StatusCard(
            title = pluralStringResource(R.plurals.home_problems_title, state.problems.size, state.problems.size),
            tone = if (state.problems.any { it.blocking }) StatusTone.PROBLEM else StatusTone.ATTENTION,
            body = if (state.checkingServer) stringResource(R.string.home_checking) else null,
            modifier = modifier,
        ) {
            state.problems.forEach { problem ->
                StatusProblemRow(
                    message = stringResource(problem.message),
                    actionLabel = stringResource(
                        if (problem.fix == null) {
                            R.string.home_check_again
                        } else {
                            R.string.action_fix
                        },
                    ),
                    onAction = { onAction(HomeAction.Fix(problem)) },
                )
            }
        }
    }
}

/** The bubble switch and snooze. */
@Composable
internal fun BubbleSection(state: HomeUiState, onAction: (HomeAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.home_bubble))
        SwitchRow(
            title = stringResource(R.string.home_bubble_switch),
            supporting = stringResource(R.string.home_bubble_switch_body),
            checked = state.bubbleEnabled,
            onCheckedChange = { onAction(HomeAction.SetBubbleEnabled(it)) },
            icon = TelltypeIcons.Bubble,
        )
        val snoozedUntil = state.snoozedUntil
        val until = snoozedUntil?.let { Formats.time(it, state.zone, currentLocale()) }
        ListItem(
            headlineContent = {
                Text(
                    text = if (until == null) {
                        stringResource(R.string.home_snooze)
                    } else {
                        stringResource(R.string.home_snoozed_until, until)
                    },
                )
            },
            leadingContent = {
                Icon(painterResource(TelltypeIcons.Snooze), contentDescription = null)
            },
            trailingContent = {
                OutlinedButton(
                    onClick = { onAction(if (until == null) HomeAction.Snooze else HomeAction.EndSnooze) },
                    enabled = state.bubbleEnabled,
                ) {
                    Text(
                        text = stringResource(
                            if (until == null) {
                                R.string.home_snooze_action
                            } else {
                                R.string.home_snooze_end
                            },
                        ),
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

/** Today's words and the typical speed, as two tiles. */
@Composable
internal fun TodaySection(stats: DictationStats, modifier: Modifier = Modifier) {
    val numbers = NumberFormat.getIntegerInstance(currentLocale())
    Column(modifier = modifier) {
        SectionHeader(text = stringResource(R.string.home_today))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Medium), modifier = Modifier.fillMaxWidth()) {
            StatTile(
                value = numbers.format(stats.wordsToday),
                label = pluralStringResource(R.plurals.home_words_label, stats.wordsToday),
                modifier = Modifier.weight(1f),
            )
            val speed = stats.typicalWordsPerMinute
            StatTile(
                value = speed?.let { numbers.format(it) } ?: stringResource(R.string.home_speed_unknown),
                label = stringResource(
                    if (speed == null) {
                        R.string.home_speed_label_unknown
                    } else {
                        R.string.home_speed_label
                    },
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(modifier = Modifier.padding(Spacing.Large)) {
            Text(text = value, style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
