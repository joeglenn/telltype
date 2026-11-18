package net.jbglenn.telltype.ui.screens.snippets

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
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.domain.port.SnippetRepository
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/** The Snippets screen: list, add, edit, delete. */
class SnippetsViewModel @Inject constructor(private val repository: SnippetRepository) : ViewModel() {
    private val editor = MutableStateFlow<SnippetEditor?>(null)
    private val pendingDelete = MutableStateFlow<SnippetId?>(null)
    private val effectChannel = Channel<SnippetsEffect>(Channel.BUFFERED)

    /** One-off effects: messages. */
    val effects: Flow<SnippetsEffect> = effectChannel.receiveAsFlow()

    /** What the screen shows. */
    val state: StateFlow<SnippetsUiState> = combine(repository.snippets, editor, pendingDelete) { all, form, deleting ->
        SnippetsUiState(
            loading = false,
            snippets = all,
            editor = form,
            pendingDelete = deleting?.let { id -> all.firstOrNull { it.id == id } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), SnippetsUiState())

    /** Handles one user action. */
    fun onAction(action: SnippetsAction) {
        when (action) {
            SnippetsAction.Add -> editor.value = SnippetEditor(editing = null, trigger = "", text = "")

            is SnippetsAction.Edit -> state.value.snippets.firstOrNull { it.id == action.id }?.let {
                editor.value = SnippetEditor(editing = it.id, trigger = it.trigger, text = it.text)
            }

            is SnippetsAction.TriggerChanged -> editor.update { it?.copy(trigger = action.value, error = null) }

            is SnippetsAction.TextChanged -> editor.update { it?.copy(text = action.value, error = null) }

            SnippetsAction.Save -> save()

            SnippetsAction.CancelEdit -> editor.value = null

            is SnippetsAction.AskDelete -> pendingDelete.value = action.id

            SnippetsAction.CancelDelete -> pendingDelete.value = null

            SnippetsAction.ConfirmDelete -> delete()
        }
    }

    private fun save() {
        val form = editor.value ?: return
        val snippets = state.value.snippets
        validateSnippet(form, snippets)?.let { error ->
            editor.value = form.copy(error = error)
            return
        }
        viewModelScope.launch {
            val existing = snippets.firstOrNull { it.id == form.editing }
            val result = if (existing == null) {
                repository.add(form.trigger.trim(), form.text)
            } else {
                repository.update(existing.copy(trigger = form.trigger.trim(), text = form.text))
            }
            if (result is Outcome.Ok) {
                editor.value = null
            } else {
                effectChannel.send(SnippetsEffect.ShowMessage(R.string.message_save_failed))
            }
        }
    }

    private fun delete() {
        val target = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch {
            if (repository.delete(target) is Outcome.Err) {
                effectChannel.send(SnippetsEffect.ShowMessage(R.string.message_save_failed))
            }
        }
    }
}
