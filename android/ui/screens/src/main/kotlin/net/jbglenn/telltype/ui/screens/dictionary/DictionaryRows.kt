package net.jbglenn.telltype.ui.screens.dictionary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.ContentListItem
import net.jbglenn.telltype.ui.designsystem.component.Tag
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.EditorCard
import net.jbglenn.telltype.ui.screens.common.EditorField

/** A word: its spelling, its sounds-like forms, where it came from, and delete. Tapping edits it. */
@Composable
internal fun WordRow(term: DictionaryTerm, onAction: (DictionaryAction) -> Unit, modifier: Modifier = Modifier) {
    val soundsLike = term.soundsLike.joinToString(", ")
    ContentListItem(
        headline = term.term,
        supporting = if (soundsLike.isEmpty()) {
            pluralStringResource(R.plurals.dictionary_uses, term.uses, term.uses)
        } else {
            stringResource(R.string.dictionary_sounds_like, soundsLike)
        },
        onClick = { onAction(DictionaryAction.EditWord(term.id)) },
        trailing = {
            RowTrailing(
                learned = term.source == EntrySource.LEARNED,
                deleteLabel = stringResource(R.string.dictionary_delete_named, term.term),
                onDelete = { onAction(DictionaryAction.AskDelete(DictionaryDelete.Word(term.id, term.term))) },
            )
        },
        modifier = modifier,
    )
}

/** A correction: "wrong → right", how often it applied, where it came from, and delete. Tapping edits it. */
@Composable
internal fun CorrectionRow(
    replacement: Replacement,
    onAction: (DictionaryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.dictionary_pair, replacement.heard, replacement.replaceWith)
    ContentListItem(
        headline = label,
        supporting = pluralStringResource(R.plurals.dictionary_uses, replacement.uses, replacement.uses),
        onClick = { onAction(DictionaryAction.EditCorrection(replacement.id)) },
        trailing = {
            RowTrailing(
                learned = replacement.source == EntrySource.LEARNED,
                deleteLabel = stringResource(R.string.dictionary_delete_named, label),
                onDelete = { onAction(DictionaryAction.AskDelete(DictionaryDelete.Correction(replacement.id, label))) },
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun RowTrailing(learned: Boolean, deleteLabel: String, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall),
        modifier = modifier,
    ) {
        if (learned) Tag(label = stringResource(R.string.dictionary_learned))
        IconButton(onClick = onDelete) {
            Icon(painter = painterResource(TelltypeIcons.Delete), contentDescription = deleteLabel)
        }
    }
}

/** The form for a word or a correction. */
@Composable
internal fun DictionaryEditorCard(
    editor: DictionaryEditor,
    onAction: (DictionaryAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = editor.error?.let { stringResource(it) }
    val adding = editor.isNew
    when (editor) {
        is DictionaryEditor.Word -> EditorCard(
            title = stringResource(if (adding) R.string.dictionary_add_word else R.string.dictionary_edit_word),
            error = error,
            onSave = { onAction(DictionaryAction.Save) },
            onCancel = { onAction(DictionaryAction.CancelEdit) },
            modifier = modifier,
        ) {
            EditorField(
                label = stringResource(R.string.dictionary_field_word),
                value = editor.term,
                onValueChange = { onAction(DictionaryAction.FirstFieldChanged(it)) },
                isError = error != null,
            )
            EditorField(
                label = stringResource(R.string.dictionary_field_sounds_like),
                supporting = stringResource(R.string.dictionary_field_sounds_like_help),
                value = editor.soundsLike,
                onValueChange = { onAction(DictionaryAction.SecondFieldChanged(it)) },
            )
        }

        is DictionaryEditor.Correction -> EditorCard(
            title = stringResource(if (adding) R.string.dictionary_add_pair else R.string.dictionary_edit_pair),
            error = error,
            onSave = { onAction(DictionaryAction.Save) },
            onCancel = { onAction(DictionaryAction.CancelEdit) },
            modifier = modifier,
        ) {
            EditorField(
                label = stringResource(R.string.dictionary_field_heard),
                value = editor.heard,
                onValueChange = { onAction(DictionaryAction.FirstFieldChanged(it)) },
                isError = error != null,
            )
            EditorField(
                label = stringResource(R.string.dictionary_field_replace),
                value = editor.replaceWith,
                onValueChange = { onAction(DictionaryAction.SecondFieldChanged(it)) },
                isError = error != null,
            )
        }
    }
}
