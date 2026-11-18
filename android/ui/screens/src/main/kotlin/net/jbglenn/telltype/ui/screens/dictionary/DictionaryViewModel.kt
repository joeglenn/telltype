package net.jbglenn.telltype.ui.screens.dictionary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/** The Dictionary screen: words with sounds-like forms, and corrections. */
class DictionaryViewModel @Inject constructor(private val dictionary: DictionaryRepository) : ViewModel() {
    private val tab = MutableStateFlow(DictionaryTab.WORDS)
    private val editor = MutableStateFlow<DictionaryEditor?>(null)
    private val pendingDelete = MutableStateFlow<DictionaryDelete?>(null)
    private val effectChannel = Channel<DictionaryEffect>(Channel.BUFFERED)

    /** One-off effects: messages. */
    val effects: Flow<DictionaryEffect> = effectChannel.receiveAsFlow()

    /** What the screen shows. */
    val state: StateFlow<DictionaryUiState> = combine(
        dictionary.terms,
        dictionary.replacements,
        tab,
        editor,
        pendingDelete,
    ) { terms, replacements, visible, form, deleting ->
        DictionaryUiState(
            loading = false,
            tab = visible,
            terms = terms,
            replacements = replacements,
            editor = form,
            pendingDelete = deleting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), DictionaryUiState())

    /** Handles one user action. */
    fun onAction(action: DictionaryAction) {
        when (action) {
            DictionaryAction.Save -> save()
            DictionaryAction.CancelEdit -> editor.value = null
            is DictionaryAction.AskDelete -> pendingDelete.value = action.target
            DictionaryAction.CancelDelete -> pendingDelete.value = null
            DictionaryAction.ConfirmDelete -> delete()
            else -> edit(action)
        }
    }

    private fun edit(action: DictionaryAction) {
        when (action) {
            is DictionaryAction.SelectTab -> {
                tab.value = action.tab
                editor.value = null
            }

            DictionaryAction.Add -> editor.value = when (tab.value) {
                DictionaryTab.WORDS -> DictionaryEditor.Word(editing = null, term = "", soundsLike = "")
                DictionaryTab.CORRECTIONS -> DictionaryEditor.Correction(editing = null, heard = "", replaceWith = "")
            }

            is DictionaryAction.EditWord -> state.value.terms.firstOrNull { it.id == action.id }?.let {
                editor.value = DictionaryEditor.Word(it.id, it.term, it.soundsLike.joinToString(", "))
            }

            is DictionaryAction.EditCorrection -> state.value.replacements.firstOrNull { it.id == action.id }?.let {
                editor.value = DictionaryEditor.Correction(it.id, it.heard, it.replaceWith)
            }

            is DictionaryAction.FirstFieldChanged -> editor.update { it?.withFirst(action.value) }

            is DictionaryAction.SecondFieldChanged -> editor.update { it?.withSecond(action.value) }

            else -> Unit
        }
    }

    private fun save() {
        when (val form = editor.value) {
            is DictionaryEditor.Word -> saveWord(form)
            is DictionaryEditor.Correction -> saveCorrection(form)
            null -> Unit
        }
    }

    private fun saveWord(form: DictionaryEditor.Word) {
        val terms = state.value.terms
        validateWord(form, terms)?.let { error ->
            editor.value = form.copy(error = error)
            return
        }
        viewModelScope.launch {
            val term = form.term.trim()
            val soundsLike = parseSoundsLike(form.soundsLike)
            val existing = terms.firstOrNull { it.id == form.editing }
            val result = if (existing == null) {
                dictionary.addTerm(term, soundsLike, EntrySource.MANUAL)
            } else {
                dictionary.updateTerm(existing.copy(term = term, soundsLike = soundsLike))
            }
            finishSave(result)
        }
    }

    private fun saveCorrection(form: DictionaryEditor.Correction) {
        val replacements = state.value.replacements
        validateCorrection(form, replacements)?.let { error ->
            editor.value = form.copy(error = error)
            return
        }
        viewModelScope.launch {
            val existing = replacements.firstOrNull { it.id == form.editing }
            val result = if (existing == null) {
                dictionary.addReplacement(form.heard.trim(), form.replaceWith.trim(), EntrySource.MANUAL)
            } else {
                dictionary.updateReplacement(
                    existing.copy(heard = form.heard.trim(), replaceWith = form.replaceWith.trim()),
                )
            }
            finishSave(result)
        }
    }

    private suspend fun finishSave(result: Outcome<*, *>) {
        if (result is Outcome.Ok) {
            editor.value = null
        } else {
            effectChannel.send(DictionaryEffect.ShowMessage(R.string.message_save_failed))
        }
    }

    private fun delete() {
        val target = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch {
            val result = when (target) {
                is DictionaryDelete.Word -> dictionary.deleteTerm(target.id)
                is DictionaryDelete.Correction -> dictionary.deleteReplacement(target.id)
            }
            if (result is Outcome.Err) effectChannel.send(DictionaryEffect.ShowMessage(R.string.message_save_failed))
        }
    }
}

private fun DictionaryEditor.withFirst(value: String): DictionaryEditor = when (this) {
    is DictionaryEditor.Word -> copy(term = value, error = null)
    is DictionaryEditor.Correction -> copy(heard = value, error = null)
}

private fun DictionaryEditor.withSecond(value: String): DictionaryEditor = when (this) {
    is DictionaryEditor.Word -> copy(soundsLike = value, error = null)
    is DictionaryEditor.Correction -> copy(replaceWith = value, error = null)
}
