package net.jbglenn.telltype.core.model.settings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/**
 * Every user setting, as one immutable tree (docs/PLAN.md Appendix G). Stored in DataStore by `:data`; changed
 * only through `SettingsRepository.update`. The per-app overrides and the exclusion list are not here: they are
 * `AppRule` rows. "Keep audio after success" is not a setting (always never).
 *
 * @property server where the server is and how it is trusted.
 * @property bubble the bubble's behaviour.
 * @property dictation recording limits.
 * @property cleanup the cleanup model.
 * @property styles the tone of each app category.
 * @property context reading text around the cursor.
 * @property dictionary learning from corrections.
 * @property insertion the insertion ladder.
 * @property privacy retention.
 * @property advanced debugging switches.
 */
public data class Settings(
    public val server: ServerConfig,
    public val bubble: BubbleSettings,
    public val dictation: DictationSettings,
    public val cleanup: CleanupSettings,
    public val styles: StyleSettings,
    public val context: ContextSettings,
    public val dictionary: DictionarySettings,
    public val insertion: InsertionSettings,
    public val privacy: PrivacySettings,
    public val advanced: AdvancedSettings,
) {
    public companion object {
        /** The defaults of Appendix G: what a fresh install starts with. */
        public val Defaults: Settings = Settings(
            server = ServerConfig.Unconfigured,
            bubble = BubbleSettings(enabled = true, side = BubbleSide.RIGHT, haptics = true, soundCues = false),
            dictation = DictationSettings(sessionLimit = 5.minutes, pauseOtherAudio = true),
            cleanup = CleanupSettings(enabled = true, modelDeadline = 2.5.seconds, skipShortPhrases = true),
            styles = StyleSettings(
                email = Tone.FORMAL,
                workChat = Tone.FORMAL,
                personalChat = Tone.CASUAL,
                notesDocuments = Tone.FORMAL,
                aiChat = Tone.AS_SPOKEN,
                code = Tone.AS_SPOKEN,
                browser = Tone.FORMAL,
                other = Tone.FORMAL,
            ),
            context = ContextSettings(readAroundCursor = true, charsBefore = 500, charsAfter = 200),
            dictionary = DictionarySettings(learnFromCorrections = LearnMode.ASK),
            insertion = InsertionSettings(neverUseClipboard = false),
            privacy = PrivacySettings(historyRetention = 30.days),
            advanced = AdvancedSettings(verboseLoggingUntil = null, useFakeEngines = false),
        )
    }
}

/** The screen edge the bubble docks to. */
public enum class BubbleSide {
    /** Left edge. */
    LEFT,

    /** Right edge (default). */
    RIGHT,
}

/**
 * Bubble group of Appendix G.
 *
 * @property enabled the bubble is shown at all.
 * @property side the default docking edge (the dragged position per orientation is remembered separately).
 * @property haptics haptic feedback on start, stop and problem (Appendix H).
 * @property soundCues start and stop tones.
 * @property look the idle bubble's colour, transparency and microphone glyph ([BubbleLook]).
 */
public data class BubbleSettings(
    public val enabled: Boolean,
    public val side: BubbleSide,
    public val haptics: Boolean,
    public val soundCues: Boolean,
    public val look: BubbleLook = BubbleLook.Default,
)

/**
 * Dictation group of Appendix G.
 *
 * @property sessionLimit a recording ends by itself after this long; a warning comes one minute before.
 *   Positive and longer than one minute.
 * @property pauseOtherAudio take audio focus while recording, so music pauses and resumes.
 */
public data class DictationSettings(public val sessionLimit: Duration, public val pauseOtherAudio: Boolean)

/**
 * Cleanup group of Appendix G.
 *
 * @property enabled send transcripts to the cleanup model; when off, rule-based cleanup only.
 * @property modelDeadline how long to wait for the model before inserting the rule-based text;
 *   [Duration.INFINITE] means "always wait".
 * @property skipShortPhrases utterances of a few words with no correction cue skip the model.
 */
public data class CleanupSettings(
    public val enabled: Boolean,
    public val modelDeadline: Duration,
    public val skipShortPhrases: Boolean,
)

/**
 * Styles group of Appendix G: the tone for each [AppCategory]. One property per category, so a tone always
 * exists. Appendix G gives no default for the browser category; it uses Formal, like "other".
 *
 * @property email tone for [AppCategory.EMAIL].
 * @property workChat tone for [AppCategory.WORK_CHAT].
 * @property personalChat tone for [AppCategory.PERSONAL_CHAT].
 * @property notesDocuments tone for [AppCategory.NOTES_DOCUMENTS].
 * @property aiChat tone for [AppCategory.AI_CHAT].
 * @property code tone for [AppCategory.CODE].
 * @property browser tone for [AppCategory.BROWSER].
 * @property other tone for [AppCategory.OTHER].
 */
public data class StyleSettings(
    public val email: Tone,
    public val workChat: Tone,
    public val personalChat: Tone,
    public val notesDocuments: Tone,
    public val aiChat: Tone,
    public val code: Tone,
    public val browser: Tone,
    public val other: Tone,
) {
    /** The tone configured for [category]. */
    public fun toneFor(category: AppCategory): Tone = when (category) {
        AppCategory.EMAIL -> email
        AppCategory.WORK_CHAT -> workChat
        AppCategory.PERSONAL_CHAT -> personalChat
        AppCategory.NOTES_DOCUMENTS -> notesDocuments
        AppCategory.AI_CHAT -> aiChat
        AppCategory.CODE -> code
        AppCategory.BROWSER -> browser
        AppCategory.OTHER -> other
    }
}

/**
 * Context group of Appendix G.
 *
 * @property readAroundCursor read text around the cursor at record start.
 * @property charsBefore at most this many characters before the cursor; `>= 0`.
 * @property charsAfter at most this many characters after the cursor; `>= 0`.
 */
public data class ContextSettings(
    public val readAroundCursor: Boolean,
    public val charsBefore: Int,
    public val charsAfter: Int,
)

/** What happens when a correction is detected after a dictation (docs/PLAN.md section 4.5). */
public enum class LearnMode {
    /** The bubble offers "Learn X → Y" (default). */
    ASK,

    /** The pair is learned at once, with Undo. */
    AUTOMATIC,

    /** Corrections are not watched. */
    OFF,
}

/**
 * Dictionary group of Appendix G.
 *
 * @property learnFromCorrections what to do with detected corrections.
 */
public data class DictionarySettings(public val learnFromCorrections: LearnMode)

/**
 * Insertion group of Appendix G.
 *
 * @property neverUseClipboard the ladder stops after the set-text step; nothing is put on the clipboard
 *   automatically (an explicit Copy tap still is).
 */
public data class InsertionSettings(public val neverUseClipboard: Boolean)

/**
 * Privacy group of Appendix G.
 *
 * @property historyRetention history rows older than this are deleted daily (archived rows are kept, D52);
 *   [Duration.ZERO] means history is off and no rows are written (how installs before A9a stored "off").
 * @property saveHistory the "Save history" switch (D53): off, no new rows are written; what is saved stays until
 *   the user deletes it or it passes [historyRetention].
 */
public data class PrivacySettings(public val historyRetention: Duration, public val saveHistory: Boolean = true) {
    /** New dictations are saved to History: the switch is on and the retention is positive. Every writer checks it. */
    public val historyOn: Boolean get() = saveHistory && historyRetention.isPositive()
}

/**
 * Advanced group of Appendix G.
 *
 * @property verboseLoggingUntil verbose debug logging (which may log dictated text) is on until this time, then
 *   off by itself; null when off. Set to one hour after the user switches it on.
 * @property useFakeEngines debug builds only: canned speech and cleanup engines, no server.
 */
public data class AdvancedSettings(public val verboseLoggingUntil: Instant?, public val useFakeEngines: Boolean)
