package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.domain.settings.ModelChoices
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.component.ChoiceOptions
import net.jbglenn.telltype.ui.designsystem.component.ChoiceRow
import net.jbglenn.telltype.ui.designsystem.component.ErrorBanner
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.screens.R

/**
 * Models (D58, D60): one row each for speech, cleanup and commands, choosing from what the server lists, with
 * "(Recommended)" after the recommended ones. Speech is typed when the server address lists no models. A choice
 * the server no longer lists is named as such above its row.
 */
@Composable
internal fun ModelsSection(
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
    expanded: String?,
    toggle: ExpandToggle,
) {
    Column {
        SectionHeader(text = stringResource(R.string.settings_models))
        if (state.configured) {
            ModelRows(state, onAction, expanded, toggle)
        } else {
            Text(
                text = stringResource(R.string.settings_models_not_set_up),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.Large, vertical = Spacing.Small),
            )
        }
    }
}

@Composable
private fun ModelRows(state: ModelsUiState, onAction: (ModelsAction) -> Unit, expanded: String?, toggle: ExpandToggle) {
    Column {
        state.problem?.let { ErrorBanner(message = stringResource(it), modifier = Modifier.padding(Spacing.Small)) }
        ModelKind.entries.forEach { kind ->
            if (state.isMissing(kind)) {
                ErrorBanner(
                    message = stringResource(R.string.settings_model_missing, state.chosenFor(kind).orEmpty()),
                    modifier = Modifier.padding(Spacing.Small),
                )
            }
            val options = state.optionsFor(kind)
            if (options.isNullOrEmpty()) {
                TypedModelRow(kind, state.chosenFor(kind), onAction)
            } else {
                ListedModelRow(kind, state, onAction, expanded == kind.name, toggle(kind.name))
            }
        }
        TextButton(
            onClick = { onAction(ModelsAction.Refresh) },
            enabled = !state.loading,
            modifier = Modifier.padding(horizontal = Spacing.Small),
        ) {
            Text(
                text = stringResource(
                    if (state.loading) R.string.settings_models_loading else R.string.settings_models_refresh,
                ),
            )
        }
    }
}

@Composable
private fun ListedModelRow(
    kind: ModelKind,
    state: ModelsUiState,
    onAction: (ModelsAction) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val chosen = state.chosenFor(kind)
    val options = state.optionsFor(kind).orEmpty()
    val recommended = stringResource(R.string.settings_model_recommended)
    val labels = options.map { ModelChoices.displayName(it.id) + if (it.recommended) " $recommended" else "" }
    val missing = chosen != null && options.none { it.id == chosen }
    ChoiceRow(
        title = stringResource(kind.titleRes()),
        options = ChoiceOptions(if (missing) labels + ModelChoices.displayName(chosen.orEmpty()) else labels),
        selectedIndex = options.indexOfFirst { it.id == chosen }.takeIf { it >= 0 }
            ?: if (missing) options.size else 0,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        onSelect = { index -> options.getOrNull(index)?.let { onAction(ModelsAction.Choose(kind, it.id)) } },
    )
}

/** A server address without a model list: the speech model is typed (`whisper-1` when left empty). */
@Composable
private fun TypedModelRow(kind: ModelKind, chosen: String?, onAction: (ModelsAction) -> Unit) {
    var text by rememberSaveable(chosen) { mutableStateOf(chosen.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(text = stringResource(kind.titleRes())) },
        placeholder = { Text(text = if (kind == ModelKind.SPEECH) ServerModels.DEFAULT_SPEECH else "") },
        supportingText = { Text(text = stringResource(R.string.settings_model_typed)) },
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = { onAction(ModelsAction.Choose(kind, text)) }) {
                Text(text = stringResource(R.string.settings_model_save))
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Large, vertical = Spacing.Small),
    )
}

private fun ModelKind.titleRes(): Int = when (this) {
    ModelKind.SPEECH -> R.string.settings_model_speech
    ModelKind.CLEANUP -> R.string.settings_model_cleanup
    ModelKind.COMMAND -> R.string.settings_model_command
}
