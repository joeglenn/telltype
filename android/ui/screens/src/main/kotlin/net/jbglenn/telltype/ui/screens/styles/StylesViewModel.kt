package net.jbglenn.telltype.ui.screens.styles

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
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.settings.StyleSettings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.port.AppRuleRepository
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/** The Styles screen: tones per category, per-app overrides, excluded apps. */
class StylesViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val rules: AppRuleRepository,
) : ViewModel() {
    private val editor = MutableStateFlow<StylesEditor?>(null)
    private val effectChannel = Channel<StylesEffect>(Channel.BUFFERED)

    /** One-off effects: messages. */
    val effects: Flow<StylesEffect> = effectChannel.receiveAsFlow()

    /** What the screen shows. */
    val state: StateFlow<StylesUiState> = combine(settings.settings, rules.rules, editor) { current, all, form ->
        StylesUiState(
            loading = false,
            tones = AppCategory.entries.map { CategoryTone(it, current.styles.toneFor(it)) },
            overrides = all.filter { it.categoryOverride != null || it.toneOverride != null },
            excluded = all.filter { it.excluded },
            editor = form,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), StylesUiState())

    /** Handles one user action. */
    fun onAction(action: StylesAction) {
        when (action) {
            is StylesAction.SetTone -> setTone(action.category, action.tone)

            StylesAction.Save -> save()

            StylesAction.CancelEdit -> editor.value = null

            is StylesAction.RemoveOverride -> change(action.app) {
                it.copy(categoryOverride = null, toneOverride = null)
            }

            is StylesAction.RemoveExclusion -> change(action.app) { it.copy(excluded = false) }

            else -> edit(action)
        }
    }

    private fun edit(action: StylesAction) {
        when (action) {
            StylesAction.AddOverride -> editor.value = StylesEditor.Override(app = "", category = null, tone = null)

            is StylesAction.EditOverride -> ruleFor(action.app)?.let {
                editor.value = StylesEditor.Override(it.app.name, it.categoryOverride, it.toneOverride)
            }

            StylesAction.AddExclusion -> editor.value = StylesEditor.Exclusion(app = "")

            is StylesAction.AppChanged -> editor.update { it?.withApp(action.value) }

            is StylesAction.CategoryChanged -> editor.update {
                (it as? StylesEditor.Override)?.copy(category = action.category, error = null)
                    ?: it
            }

            is StylesAction.ToneChanged -> editor.update {
                (it as? StylesEditor.Override)?.copy(tone = action.tone, error = null)
                    ?: it
            }

            else -> Unit
        }
    }

    private fun setTone(category: AppCategory, tone: Tone) {
        viewModelScope.launch {
            val result = settings.update { it.copy(styles = it.styles.withTone(category, tone)) }
            if (result is Outcome.Err) effectChannel.send(StylesEffect.ShowMessage(R.string.message_save_failed))
        }
    }

    private fun save() {
        val form = editor.value ?: return
        validateStylesEditor(form)?.let { error ->
            editor.value = form.withError(error)
            return
        }
        val app = AppPackage(form.app.trim())
        val existing = ruleFor(app) ?: AppRule(app, categoryOverride = null, toneOverride = null, excluded = false)
        val updated = when (form) {
            is StylesEditor.Override -> existing.copy(categoryOverride = form.category, toneOverride = form.tone)
            is StylesEditor.Exclusion -> existing.copy(excluded = true)
        }
        viewModelScope.launch {
            if (rules.upsert(updated) is Outcome.Ok) {
                editor.value = null
            } else {
                effectChannel.send(StylesEffect.ShowMessage(R.string.message_save_failed))
            }
        }
    }

    /** Applies [transform] to [app]'s rule; a rule left with nothing in it is deleted. */
    private fun change(app: AppPackage, transform: (AppRule) -> AppRule) {
        val rule = ruleFor(app) ?: return
        val updated = transform(rule)
        viewModelScope.launch {
            val empty = !updated.excluded && updated.categoryOverride == null && updated.toneOverride == null
            val result = if (empty) rules.delete(app) else rules.upsert(updated)
            if (result is Outcome.Err) effectChannel.send(StylesEffect.ShowMessage(R.string.message_save_failed))
        }
    }

    private fun ruleFor(app: AppPackage): AppRule? =
        (state.value.overrides + state.value.excluded).firstOrNull { it.app == app }
}

private fun StylesEditor.withApp(value: String): StylesEditor = when (this) {
    is StylesEditor.Override -> copy(app = value, error = null)
    is StylesEditor.Exclusion -> copy(app = value, error = null)
}

private fun StylesEditor.withError(error: Int): StylesEditor = when (this) {
    is StylesEditor.Override -> copy(error = error)
    is StylesEditor.Exclusion -> copy(error = error)
}

/** [StyleSettings] with [category]'s tone replaced. */
internal fun StyleSettings.withTone(category: AppCategory, tone: Tone): StyleSettings = when (category) {
    AppCategory.EMAIL -> copy(email = tone)
    AppCategory.WORK_CHAT -> copy(workChat = tone)
    AppCategory.PERSONAL_CHAT -> copy(personalChat = tone)
    AppCategory.NOTES_DOCUMENTS -> copy(notesDocuments = tone)
    AppCategory.AI_CHAT -> copy(aiChat = tone)
    AppCategory.CODE -> copy(code = tone)
    AppCategory.BROWSER -> copy(browser = tone)
    AppCategory.OTHER -> copy(other = tone)
}
