package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlin.time.Duration
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.ui.designsystem.component.ActionRow
import net.jbglenn.telltype.ui.designsystem.component.ChoiceOptions
import net.jbglenn.telltype.ui.designsystem.component.ChoiceRow
import net.jbglenn.telltype.ui.designsystem.component.LinkRow
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.component.SwitchRow
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.Formats
import net.jbglenn.telltype.ui.screens.common.currentLocale
import net.jbglenn.telltype.ui.screens.common.durationText

/** Cleanup: on or off, how long to wait for the model, short phrases, styles, learning. */
@Composable
internal fun CleanupSection(
    settings: Settings,
    onAction: (SettingsAction) -> Unit,
    expanded: String?,
    toggle: ExpandToggle,
    onNavigate: (SettingsDestination) -> Unit,
) {
    val cleanup = settings.cleanup
    Column {
        SectionHeader(text = stringResource(R.string.settings_cleanup))
        SwitchRow(
            title = stringResource(R.string.settings_cleanup_enabled),
            supporting = stringResource(R.string.settings_cleanup_enabled_body),
            checked = cleanup.enabled,
            onCheckedChange = { onAction(SettingsAction.CleanupEnabled(it)) },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_model_deadline),
            options = ChoiceOptions(SettingsChoices.ModelDeadlines.map { deadlineLabel(it) }),
            selectedIndex = nearestChoice(SettingsChoices.ModelDeadlines, cleanup.modelDeadline),
            expanded = expanded == "deadline",
            onExpandedChange = toggle("deadline"),
            onSelect = { onAction(SettingsAction.ModelDeadline(SettingsChoices.ModelDeadlines[it])) },
        )
        SwitchRow(
            title = stringResource(R.string.settings_skip_short),
            supporting = stringResource(R.string.settings_skip_short_body),
            checked = cleanup.skipShortPhrases,
            onCheckedChange = { onAction(SettingsAction.SkipShortPhrases(it)) },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_learn),
            options = ChoiceOptions(SettingsChoices.LearnModes.map { stringResource(it.label) }),
            selectedIndex = SettingsChoices.LearnModes.indexOf(settings.dictionary.learnFromCorrections),
            expanded = expanded == "learn",
            onExpandedChange = toggle("learn"),
            onSelect = { onAction(SettingsAction.Learn(SettingsChoices.LearnModes[it])) },
        )
        LinkRow(
            title = stringResource(R.string.settings_styles),
            supporting = stringResource(R.string.settings_styles_body),
            onClick = { onNavigate(SettingsDestination.STYLES) },
        )
    }
}

/** Privacy: the "Save history" switch and how long to keep it (D53), clearing history, excluded apps. */
@Composable
internal fun PrivacySection(
    settings: Settings,
    historyCount: Int?,
    onAction: (SettingsAction) -> Unit,
    expanded: String?,
    toggle: ExpandToggle,
    onNavigate: (SettingsDestination) -> Unit,
) {
    val historyOn = settings.privacy.historyOn
    Column {
        SectionHeader(text = stringResource(R.string.settings_privacy))
        SwitchRow(
            title = stringResource(R.string.settings_save_history),
            supporting = stringResource(
                if (historyOn) R.string.settings_save_history_on else R.string.settings_save_history_off,
            ),
            checked = historyOn,
            onCheckedChange = { onAction(SettingsAction.SaveHistory(it)) },
        )
        ChoiceRow(
            title = stringResource(R.string.settings_keep_history),
            options = ChoiceOptions(SettingsChoices.Retention.map { retentionLabel(it) }),
            selectedIndex = nearestChoice(SettingsChoices.Retention, settings.privacy.historyRetention),
            expanded = expanded == "retention",
            onExpandedChange = toggle("retention"),
            onSelect = { onAction(SettingsAction.HistoryRetention(SettingsChoices.Retention[it])) },
            enabled = historyOn,
        )
        ActionRow(
            title = stringResource(R.string.settings_clear_history),
            supporting = when (historyCount) {
                null -> null
                0 -> stringResource(R.string.settings_clear_history_empty)
                else -> pluralStringResource(R.plurals.settings_clear_history_body, historyCount, historyCount)
            },
            destructive = true,
            enabled = historyCount != null && historyCount > 0,
            onClick = { onAction(SettingsAction.AskClearHistory) },
        )
        LinkRow(
            title = stringResource(R.string.settings_excluded),
            supporting = stringResource(R.string.settings_excluded_body),
            onClick = { onNavigate(SettingsDestination.STYLES) },
        )
    }
}

/** Advanced: verbose logging (switches itself off), fake engines (debug builds), Diagnostics. */
@Composable
internal fun AdvancedSection(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    showDebugOptions: Boolean,
    onNavigate: (SettingsDestination) -> Unit,
) {
    val until = state.settings?.advanced?.verboseLoggingUntil
    Column {
        SectionHeader(text = stringResource(R.string.settings_advanced))
        SwitchRow(
            title = stringResource(R.string.settings_verbose),
            supporting = if (state.verboseLogging && until != null) {
                stringResource(R.string.settings_verbose_until, Formats.time(until, state.zone, currentLocale()))
            } else {
                stringResource(R.string.settings_verbose_body)
            },
            checked = state.verboseLogging,
            onCheckedChange = { onAction(SettingsAction.VerboseLogging(it)) },
        )
        if (showDebugOptions) {
            SwitchRow(
                title = stringResource(R.string.settings_fake_engines),
                supporting = stringResource(R.string.settings_fake_engines_body),
                checked = state.settings?.advanced?.useFakeEngines == true,
                onCheckedChange = { onAction(SettingsAction.FakeEngines(it)) },
            )
        }
        LinkRow(
            title = stringResource(R.string.settings_diagnostics),
            supporting = stringResource(R.string.settings_diagnostics_body),
            onClick = { onNavigate(SettingsDestination.DIAGNOSTICS) },
        )
    }
}

@Composable
private fun deadlineLabel(deadline: Duration): String = if (deadline.isInfinite()) {
    stringResource(R.string.settings_deadline_always)
} else {
    stringResource(R.string.settings_deadline_up_to, durationText(deadline))
}

@Composable
private fun retentionLabel(retention: Duration): String =
    pluralStringResource(R.plurals.settings_days, retention.inWholeDays.toInt(), retention.inWholeDays.toInt())

private val LearnMode.label: Int
    get() = when (this) {
        LearnMode.ASK -> R.string.settings_learn_ask
        LearnMode.AUTOMATIC -> R.string.settings_learn_automatic
        LearnMode.OFF -> R.string.settings_learn_off
    }
