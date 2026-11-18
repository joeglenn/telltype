package net.jbglenn.telltype.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.settings.PrivacySettings
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.LocalTime
import net.jbglenn.telltype.ui.screens.common.StopTimeout
import net.jbglenn.telltype.ui.screens.history.HistoryEraser

/** Settings: every row of Appendix G, saved as it changes, and "Clear history now". */
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val eraser: HistoryEraser,
    private val time: LocalTime,
) : ViewModel() {
    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    private val sheets = MutableStateFlow(PrivacySheets())

    /** One-off effects: messages. */
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    /** What the screen shows. */
    val state: StateFlow<SettingsUiState> =
        combine(repository.settings, eraser.count, eraser.archivedCount, sheets) { settings, count, archived, open ->
            SettingsUiState(
                settings = settings,
                now = time.now(),
                zone = time.zone(),
                historyCount = count,
                confirmingClear = open.confirmingClear,
                archivedCount = archived,
                clearIncludesArchive = open.clearIncludesArchive,
                confirmingHistoryOff = open.confirmingOff,
                offDeletesSaved = open.offDeletesSaved,
                offDeletesArchive = open.offDeletesArchive,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), SettingsUiState(zone = time.zone()))

    /** Applies one change. */
    fun onAction(action: SettingsAction) {
        when (action) {
            SettingsAction.AskClearHistory -> sheets.value = PrivacySheets(confirmingClear = true)
            SettingsAction.CancelClearHistory -> sheets.value = PrivacySheets()
            SettingsAction.ConfirmClearHistory -> clearHistory()
            is SettingsAction.ClearIncludesArchive -> sheets.update { it.copy(clearIncludesArchive = action.value) }
            else -> onHistorySwitch(action)
        }
    }

    /** "Save history" (D53): on at once; off asks first when something is saved, and may delete it. */
    private fun onHistorySwitch(action: SettingsAction) {
        when (action) {
            is SettingsAction.SaveHistory -> if (action.value) save(action) else askHistoryOff()

            is SettingsAction.HistoryOffDeletesSaved -> sheets.update {
                it.copy(offDeletesSaved = action.value, offDeletesArchive = it.offDeletesArchive && action.value)
            }

            is SettingsAction.HistoryOffDeletesArchive -> sheets.update { it.copy(offDeletesArchive = action.value) }

            SettingsAction.CancelHistoryOff -> sheets.value = PrivacySheets()

            SettingsAction.ConfirmHistoryOff -> switchHistoryOff()

            else -> save(action)
        }
    }

    private fun askHistoryOff() {
        viewModelScope.launch {
            if (eraser.count.first() > 0) {
                sheets.value = PrivacySheets(confirmingOff = true)
            } else {
                save(SettingsAction.SaveHistory(false))
            }
        }
    }

    private fun switchHistoryOff() {
        val choice = sheets.value
        sheets.value = PrivacySheets()
        save(SettingsAction.SaveHistory(false))
        if (!choice.offDeletesSaved && !choice.offDeletesArchive) return
        viewModelScope.launch {
            val message = when (eraser.deleteAll(includeArchived = choice.offDeletesArchive)) {
                is Outcome.Ok -> R.string.history_cleared
                is Outcome.Err -> R.string.history_clear_failed
            }
            effectChannel.send(SettingsEffect.ShowMessage(message))
        }
    }

    private fun save(action: SettingsAction) {
        val now = time.now()
        viewModelScope.launch {
            val result = repository.update { apply(it, action, now) }
            if (result is Outcome.Err) effectChannel.send(SettingsEffect.ShowMessage(R.string.message_save_failed))
        }
    }

    private fun clearHistory() {
        val includeArchive = sheets.value.clearIncludesArchive
        sheets.value = PrivacySheets()
        viewModelScope.launch {
            val message = when (eraser.deleteAll(includeArchived = includeArchive)) {
                is Outcome.Ok -> R.string.history_cleared
                is Outcome.Err -> R.string.history_clear_failed
            }
            effectChannel.send(SettingsEffect.ShowMessage(message))
        }
    }

    private fun apply(settings: Settings, action: SettingsAction, now: Instant): Settings = with(settings) {
        when (action) {
            is SettingsAction.BubbleEnabled -> copy(bubble = bubble.copy(enabled = action.value))
            is SettingsAction.BubbleSideChanged -> copy(bubble = bubble.copy(side = action.value))
            is SettingsAction.BubbleLookChanged -> copy(bubble = bubble.copy(look = action.value))
            is SettingsAction.Haptics -> copy(bubble = bubble.copy(haptics = action.value))
            is SettingsAction.SoundCues -> copy(bubble = bubble.copy(soundCues = action.value))
            is SettingsAction.SessionLimit -> copy(dictation = dictation.copy(sessionLimit = action.value))
            is SettingsAction.PauseOtherAudio -> copy(dictation = dictation.copy(pauseOtherAudio = action.value))
            else -> applyOther(settings, action, now)
        }
    }

    private fun applyOther(settings: Settings, action: SettingsAction, now: Instant): Settings = with(settings) {
        when (action) {
            is SettingsAction.CleanupEnabled -> copy(cleanup = cleanup.copy(enabled = action.value))

            is SettingsAction.ModelDeadline -> copy(cleanup = cleanup.copy(modelDeadline = action.value))

            is SettingsAction.SkipShortPhrases -> copy(cleanup = cleanup.copy(skipShortPhrases = action.value))

            is SettingsAction.ReadAroundCursor -> copy(context = context.copy(readAroundCursor = action.value))

            is SettingsAction.Learn -> copy(dictionary = dictionary.copy(learnFromCorrections = action.value))

            is SettingsAction.NeverUseClipboard -> copy(insertion = insertion.copy(neverUseClipboard = action.value))

            is SettingsAction.HistoryRetention -> copy(privacy = privacy.copy(historyRetention = action.value))

            is SettingsAction.SaveHistory -> copy(privacy = switchedHistory(privacy, action.value))

            is SettingsAction.VerboseLogging ->
                copy(
                    advanced = advanced.copy(
                        verboseLoggingUntil = if (action.value) now + VerboseLoggingLength else null,
                    ),
                )

            is SettingsAction.FakeEngines -> copy(advanced = advanced.copy(useFakeEngines = action.value))

            else -> this
        }
    }

    private companion object {
        val VerboseLoggingLength = 1.hours

        /** The switch; switching on restores the default retention when the stored one is the old "off" (zero). */
        fun switchedHistory(privacy: PrivacySettings, on: Boolean): PrivacySettings = privacy.copy(
            saveHistory = on,
            historyRetention = if (on && !privacy.historyRetention.isPositive()) {
                Settings.Defaults.privacy.historyRetention
            } else {
                privacy.historyRetention
            },
        )
    }
}

/**
 * The questions of the Privacy section and their ticks.
 *
 * @property confirmingClear "Clear history" is open.
 * @property clearIncludesArchive its "also delete the Archive".
 * @property confirmingOff the question after switching "Save history" off is open.
 * @property offDeletesSaved its "delete the saved dictations".
 * @property offDeletesArchive its "also delete the Archive".
 */
internal data class PrivacySheets(
    val confirmingClear: Boolean = false,
    val clearIncludesArchive: Boolean = false,
    val confirmingOff: Boolean = false,
    val offDeletesSaved: Boolean = false,
    val offDeletesArchive: Boolean = false,
)
