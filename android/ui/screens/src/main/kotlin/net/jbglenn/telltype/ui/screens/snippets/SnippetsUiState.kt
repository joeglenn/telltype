package net.jbglenn.telltype.ui.screens.snippets

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.ui.screens.R

/**
 * The Snippets screen (docs/PLAN.md sections 4.6 and 5.3): trigger phrases and the text they insert. Snippet text
 * is personal data: it stays in memory here and is never logged or put in saved state.
 *
 * @property loading nothing has arrived yet.
 * @property snippets all snippets, alphabetical by trigger.
 * @property editor the open form, null when none is open.
 * @property pendingDelete the snippet whose deletion awaits confirmation.
 */
@Immutable
data class SnippetsUiState(
    val loading: Boolean = true,
    val snippets: List<Snippet> = emptyList(),
    val editor: SnippetEditor? = null,
    val pendingDelete: Snippet? = null,
)

/**
 * The add or edit form.
 *
 * @property editing the snippet being edited, null for a new one.
 * @property trigger the phrase to say.
 * @property text the text to insert.
 * @property error the validation message, null while the input is acceptable.
 */
@Immutable
data class SnippetEditor(
    val editing: SnippetId?,
    val trigger: String,
    val text: String,
    @param:StringRes val error: Int? = null,
) {
    override fun toString(): String =
        "SnippetEditor(editing=$editing, trigger=‹${trigger.length} chars›, text=‹${text.length} chars›, error=$error)"
}

/** What the user did on the Snippets screen. */
sealed interface SnippetsAction {
    /** Opened an empty form. */
    data object Add : SnippetsAction

    /**
     * Opened a snippet for editing.
     *
     * @property id the snippet.
     */
    data class Edit(val id: SnippetId) : SnippetsAction

    /**
     * Typed in the trigger field.
     *
     * @property value the text.
     */
    data class TriggerChanged(val value: String) : SnippetsAction

    /**
     * Typed in the text field.
     *
     * @property value the text.
     */
    data class TextChanged(val value: String) : SnippetsAction

    /** Saved the form. */
    data object Save : SnippetsAction

    /** Closed the form without saving. */
    data object CancelEdit : SnippetsAction

    /**
     * Asked to delete a snippet.
     *
     * @property id the snippet.
     */
    data class AskDelete(val id: SnippetId) : SnippetsAction

    /** Confirmed the deletion. */
    data object ConfirmDelete : SnippetsAction

    /** Kept the snippet. */
    data object CancelDelete : SnippetsAction
}

/** One-off effects of the Snippets screen. */
sealed interface SnippetsEffect {
    /**
     * A short message.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : SnippetsEffect
}

/** Validates the form against the existing [snippets]; null when it may be saved. */
@StringRes
fun validateSnippet(editor: SnippetEditor, snippets: List<Snippet>): Int? = when {
    editor.trigger.isBlank() -> R.string.snippets_error_trigger_blank

    editor.text.isBlank() -> R.string.snippets_error_text_blank

    snippets.any { it.id != editor.editing && it.trigger.equals(editor.trigger.trim(), ignoreCase = true) } ->
        R.string.snippets_error_duplicate

    else -> null
}
