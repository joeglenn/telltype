package net.jbglenn.telltype.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.core.model.settings.Settings

/**
 * Settings (docs/PLAN.md Appendix G).
 *
 * @property settings the stored settings; null while loading.
 * @property now the time now, to show when verbose logging switches itself off.
 * @property zone the zone times are shown in.
 * @property historyCount how many dictations are saved, History and Archive together; null while loading.
 * @property archivedCount how many of them are in the Archive.
 * @property confirmingClear the "Clear history" confirmation is open.
 * @property clearIncludesArchive the confirmation's "also delete the Archive" is ticked.
 * @property confirmingHistoryOff the question after switching "Save history" off is open (D53).
 * @property offDeletesSaved that question's "delete the saved dictations" is ticked.
 * @property offDeletesArchive that question's "also delete the Archive" is ticked.
 */
@Immutable
data class SettingsUiState(
    val settings: Settings? = null,
    val now: Instant? = null,
    val zone: ZoneId = ZoneId.of("UTC"),
    val historyCount: Int? = null,
    val confirmingClear: Boolean = false,
    val archivedCount: Int = 0,
    val clearIncludesArchive: Boolean = false,
    val confirmingHistoryOff: Boolean = false,
    val offDeletesSaved: Boolean = false,
    val offDeletesArchive: Boolean = false,
) {
    /** Saved dictations outside the Archive. */
    val unarchivedCount: Int get() = ((historyCount ?: 0) - archivedCount).coerceAtLeast(0)

    /** Verbose logging is on now. */
    val verboseLogging: Boolean
        get() {
            val until = settings?.advanced?.verboseLoggingUntil ?: return false
            return now == null || until > now
        }
}

/** One change on the Settings screen; each maps to one Appendix G row. */
sealed interface SettingsAction {
    /** @property value the new value. */
    data class BubbleEnabled(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class BubbleSideChanged(val value: BubbleSide) : SettingsAction

    /** @property value the idle bubble's new colour, transparency and microphone glyph. */
    data class BubbleLookChanged(val value: BubbleLook) : SettingsAction

    /** @property value the new value. */
    data class Haptics(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class SoundCues(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class SessionLimit(val value: Duration) : SettingsAction

    /** @property value the new value. */
    data class PauseOtherAudio(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class CleanupEnabled(val value: Boolean) : SettingsAction

    /** @property value the new value; [Duration.INFINITE] waits always. */
    data class ModelDeadline(val value: Duration) : SettingsAction

    /** @property value the new value. */
    data class SkipShortPhrases(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class ReadAroundCursor(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class Learn(val value: LearnMode) : SettingsAction

    /** @property value the new value. */
    data class NeverUseClipboard(val value: Boolean) : SettingsAction

    /** @property value how long History keeps a dictation (archived ones are kept until deleted). */
    data class HistoryRetention(val value: Duration) : SettingsAction

    /** @property value the "Save history" switch; off asks first when something is saved. */
    data class SaveHistory(val value: Boolean) : SettingsAction

    /** @property value the off question's "delete the saved dictations". */
    data class HistoryOffDeletesSaved(val value: Boolean) : SettingsAction

    /** @property value the off question's "also delete the Archive". */
    data class HistoryOffDeletesArchive(val value: Boolean) : SettingsAction

    /** Confirmed switching "Save history" off, deleting what the question's ticks say. */
    data object ConfirmHistoryOff : SettingsAction

    /** Cancelled switching "Save history" off: it stays on. */
    data object CancelHistoryOff : SettingsAction

    /** @property value the clear question's "also delete the Archive". */
    data class ClearIncludesArchive(val value: Boolean) : SettingsAction

    /** @property value on switches it on for one hour. */
    data class VerboseLogging(val value: Boolean) : SettingsAction

    /** @property value the new value. */
    data class FakeEngines(val value: Boolean) : SettingsAction

    /** "Clear history now": ask first. */
    data object AskClearHistory : SettingsAction

    /** Confirmed clearing the whole history. */
    data object ConfirmClearHistory : SettingsAction

    /** Cancelled clearing the whole history. */
    data object CancelClearHistory : SettingsAction
}

/** One-off effects of Settings. */
sealed interface SettingsEffect {
    /**
     * A short message.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : SettingsEffect
}

/** Where a row of Settings leads (handled by navigation, not the ViewModel). */
enum class SettingsDestination {
    /** Server setup. */
    SERVER,

    /** Styles and excluded apps. */
    STYLES,

    /** Diagnostics. */
    DIAGNOSTICS,
}

/** The offered values of the choice rows (Appendix G defaults included). */
internal object SettingsChoices {
    val SessionLimits: List<Duration> = listOf(2.minutes, 5.minutes, 10.minutes, 15.minutes)
    val ModelDeadlines: List<Duration> = listOf(1.5.seconds, 2.5.seconds, 5.seconds, Duration.INFINITE)
    val Retention: List<Duration> = listOf(7.days, 30.days, 90.days)
    val LearnModes: List<LearnMode> = listOf(LearnMode.ASK, LearnMode.AUTOMATIC, LearnMode.OFF)
    val Sides: List<BubbleSide> = listOf(BubbleSide.LEFT, BubbleSide.RIGHT)
}

/** The index of [value] in [choices], or of the nearest offered value when the stored one is not offered. */
internal fun nearestChoice(choices: List<Duration>, value: Duration): Int {
    val exact = choices.indexOf(value)
    if (exact >= 0) return exact
    return choices.indices.filter { choices[it].isFinite() }.minBy { (choices[it] - value).absoluteValue }
}
