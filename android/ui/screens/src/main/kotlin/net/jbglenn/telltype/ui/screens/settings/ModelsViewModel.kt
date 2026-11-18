package net.jbglenn.telltype.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.settings.ModelRole
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.ServerModelCatalog
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.settings.ModelRoles
import net.jbglenn.telltype.ui.designsystem.error.connectionMessageRes
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/**
 * Settings → Models (D58, D60): the speech, cleanup and command model rows, listed from the server's
 * `/v1/models` (speech from the server address, the others from the text model address). Loads the lists when
 * the screen opens and on Refresh; a choice is saved at once.
 */
class ModelsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val catalog: ServerModelCatalog,
) : ViewModel() {
    private val lists = MutableStateFlow(ModelLists())

    /** What the Models section shows. */
    val state: StateFlow<ModelsUiState> = combine(repository.settings, lists) { settings, loaded ->
        ModelsUiState(
            configured = settings.server.isConfigured,
            chosen = settings.server.models,
            speech = loaded.speech?.let { options(it, ModelRole.SPEECH) },
            cleanup = loaded.text?.let { options(it, ModelRole.CLEANUP) },
            command = loaded.text?.let { options(it, ModelRole.COMMANDS) },
            loading = loaded.loading,
            problem = loaded.problem,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), ModelsUiState())

    init {
        refresh()
    }

    /** Handles one action. */
    fun onAction(action: ModelsAction) {
        when (action) {
            ModelsAction.Refresh -> refresh()

            is ModelsAction.Choose -> viewModelScope.launch {
                repository.update { it.copy(server = it.server.copy(models = chose(it.server.models, action))) }
            }
        }
    }

    private fun refresh() {
        lists.value = lists.value.copy(loading = true, problem = null)
        viewModelScope.launch {
            if (!repository.current().server.isConfigured) {
                lists.value = ModelLists()
                return@launch
            }
            val speech = catalog.list(ServerRole.SPEECH)
            val text = catalog.list(ServerRole.TEXT)
            val failure = (text as? Outcome.Err)?.error ?: (speech as? Outcome.Err)?.error
            lists.value = ModelLists(
                speech = (speech as? Outcome.Ok)?.value,
                text = (text as? Outcome.Ok)?.value,
                loading = false,
                problem = failure?.connectionMessageRes(),
            )
        }
    }

    /** [role]'s list (B10): only its models, each file once, "(Recommended)" once per recommended kind. */
    private fun options(models: List<OfferedModel>, role: ModelRole): List<ModelOption> {
        val shown = ModelRoles.forRole(models, role)
        val recommended = ModelRoles.recommendedIds(shown, role)
        return shown.map { ModelOption(it.id, recommended = it.id in recommended) }
    }

    private fun chose(models: ServerModels, choice: ModelsAction.Choose): ServerModels {
        val name = choice.name.trim().takeIf(String::isNotEmpty)
        return when (choice.kind) {
            ModelKind.SPEECH -> models.copy(speech = name)
            ModelKind.CLEANUP -> models.copy(cleanup = name)
            ModelKind.COMMAND -> models.copy(command = name)
        }
    }

    /** The lists as loaded. */
    private data class ModelLists(
        val speech: List<OfferedModel>? = null,
        val text: List<OfferedModel>? = null,
        val loading: Boolean = false,
        val problem: Int? = null,
    )
}
