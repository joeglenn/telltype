package net.jbglenn.telltype.ui.screens.dictionary

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.ui.screens.R

/**
 * The Dictionary screen (docs/PLAN.md sections 4.5 and 5.3): words spelled your way with their sounds-like
 * forms, and corrections (wrong-to-right pairs, typed in or learned).
 *
 * @property loading nothing has arrived yet.
 * @property tab the visible list.
 * @property terms words, alphabetical.
 * @property replacements corrections, alphabetical by the wrong form.
 * @property editor the open form, null when none is open.
 * @property pendingDelete the entry whose deletion awaits confirmation.
 */
@Immutable
data class DictionaryUiState(
    val loading: Boolean = true,
    val tab: DictionaryTab = DictionaryTab.WORDS,
    val terms: List<DictionaryTerm> = emptyList(),
    val replacements: List<Replacement> = emptyList(),
    val editor: DictionaryEditor? = null,
    val pendingDelete: DictionaryDelete? = null,
)

/** The two lists. */
enum class DictionaryTab {
    /** Words and names. */
    WORDS,

    /** Wrong-to-right pairs. */
    CORRECTIONS,
}

/** The open form. */
@Immutable
sealed interface DictionaryEditor {
    /** The message for a validation problem, null when the input is acceptable so far. */
    val error: Int?

    /** A new entry rather than an edit of an existing one. */
    val isNew: Boolean

    /**
     * Add or edit a word.
     *
     * @property editing the word being edited, null for a new one.
     * @property term the spelling.
     * @property soundsLike comma-separated misheard forms.
     */
    data class Word(
        val editing: DictionaryTermId?,
        val term: String,
        val soundsLike: String,
        @param:StringRes override val error: Int? = null,
    ) : DictionaryEditor {
        override val isNew: Boolean get() = editing == null
    }

    /**
     * Add or edit a correction.
     *
     * @property editing the pair being edited, null for a new one.
     * @property heard the wrong form.
     * @property replaceWith the right form.
     */
    data class Correction(
        val editing: ReplacementId?,
        val heard: String,
        val replaceWith: String,
        @param:StringRes override val error: Int? = null,
    ) : DictionaryEditor {
        override val isNew: Boolean get() = editing == null
    }
}

/** An entry waiting for delete confirmation. */
@Immutable
sealed interface DictionaryDelete {
    /** The entry's text, for the question. */
    val label: String

    /**
     * A word.
     *
     * @property id its id.
     * @property label its text, for the question.
     */
    data class Word(val id: DictionaryTermId, override val label: String) : DictionaryDelete

    /**
     * A correction.
     *
     * @property id its id.
     * @property label its text, for the question.
     */
    data class Correction(val id: ReplacementId, override val label: String) : DictionaryDelete
}

/** What the user did on the Dictionary screen. */
sealed interface DictionaryAction {
    /**
     * Switched lists.
     *
     * @property tab the list.
     */
    data class SelectTab(val tab: DictionaryTab) : DictionaryAction

    /** Opened an empty form for the visible list. */
    data object Add : DictionaryAction

    /**
     * Opened a word for editing.
     *
     * @property id the word.
     */
    data class EditWord(val id: DictionaryTermId) : DictionaryAction

    /**
     * Opened a correction for editing.
     *
     * @property id the pair.
     */
    data class EditCorrection(val id: ReplacementId) : DictionaryAction

    /**
     * Typed in the first field (word, or wrong form).
     *
     * @property value the text.
     */
    data class FirstFieldChanged(val value: String) : DictionaryAction

    /**
     * Typed in the second field (sounds-like forms, or right form).
     *
     * @property value the text.
     */
    data class SecondFieldChanged(val value: String) : DictionaryAction

    /** Saved the form. */
    data object Save : DictionaryAction

    /** Closed the form without saving. */
    data object CancelEdit : DictionaryAction

    /**
     * Asked to delete an entry.
     *
     * @property target what to delete.
     */
    data class AskDelete(val target: DictionaryDelete) : DictionaryAction

    /** Confirmed the deletion. */
    data object ConfirmDelete : DictionaryAction

    /** Kept the entry. */
    data object CancelDelete : DictionaryAction
}

/** One-off effects of the Dictionary screen. */
sealed interface DictionaryEffect {
    /**
     * A short message.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : DictionaryEffect
}

/** Splits "osiris, oh siris" into its forms: trimmed, without blanks or duplicates. */
fun parseSoundsLike(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

/** Validates a word form against the existing [terms]; null when it may be saved. */
@StringRes
fun validateWord(editor: DictionaryEditor.Word, terms: List<DictionaryTerm>): Int? = when {
    editor.term.isBlank() -> R.string.dictionary_error_word_blank

    terms.any { it.id != editor.editing && it.term.equals(editor.term.trim(), ignoreCase = true) } ->
        R.string.dictionary_error_word_duplicate

    else -> null
}

/** Validates a correction form against the existing [replacements]; null when it may be saved. */
@StringRes
fun validateCorrection(editor: DictionaryEditor.Correction, replacements: List<Replacement>): Int? = when {
    editor.heard.isBlank() || editor.replaceWith.isBlank() -> R.string.dictionary_error_pair_blank

    editor.heard.trim() == editor.replaceWith.trim() -> R.string.dictionary_error_pair_same

    replacements.any { it.id != editor.editing && it.heard.equals(editor.heard.trim(), ignoreCase = true) } ->
        R.string.dictionary_error_pair_duplicate

    else -> null
}
