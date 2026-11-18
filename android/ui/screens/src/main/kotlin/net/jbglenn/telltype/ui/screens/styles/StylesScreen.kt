package net.jbglenn.telltype.ui.screens.styles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.WidthClass
import net.jbglenn.telltype.ui.designsystem.component.ChoiceOptions
import net.jbglenn.telltype.ui.designsystem.component.ChoiceRow
import net.jbglenn.telltype.ui.designsystem.component.LoadingState
import net.jbglenn.telltype.ui.designsystem.component.SectionHeader
import net.jbglenn.telltype.ui.designsystem.component.TopBar
import net.jbglenn.telltype.ui.designsystem.screenContent
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.ToneChoices
import net.jbglenn.telltype.ui.screens.common.label

/** Styles (docs/PLAN.md section 5.3). Stateless apart from which tone row is expanded. */
@Composable
fun StylesScreen(
    state: StylesUiState,
    onAction: (StylesAction) -> Unit,
    modifier: Modifier = Modifier,
    widthClass: WidthClass = WidthClass.COMPACT,
    onNavigateUp: (() -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { TopBar(title = stringResource(R.string.styles_title), onNavigateUp = onNavigateUp) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { insets ->
        if (state.loading) {
            LoadingState(modifier = Modifier.padding(insets))
        } else {
            val toneLabels = ChoiceOptions(ToneChoices.map { stringResource(it.label) })
            LazyColumn(
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = Spacing.ExtraLarge),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            ) {
                item(key = "tones-header") {
                    Column(modifier = Modifier.screenContent(widthClass)) {
                        SectionHeader(text = stringResource(R.string.styles_tones))
                        Text(
                            text = stringResource(R.string.styles_tones_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.tones, key = { "tone-${it.category.id}" }) { row ->
                    ChoiceRow(
                        title = stringResource(row.category.label),
                        options = toneLabels,
                        selectedIndex = ToneChoices.indexOf(row.tone),
                        expanded = expanded == row.category.id,
                        onExpandedChange = { open -> expanded = if (open) row.category.id else null },
                        onSelect = { index -> onAction(StylesAction.SetTone(row.category, ToneChoices[index])) },
                        modifier = Modifier.screenContent(widthClass),
                    )
                }
                overrideSection(state, onAction, widthClass)
                exclusionSection(state, onAction, widthClass)
            }
        }
    }
}

private fun LazyListScope.overrideSection(
    state: StylesUiState,
    onAction: (StylesAction) -> Unit,
    widthClass: WidthClass,
) {
    item(key = "overrides-header") {
        SectionHeaderWithAdd(
            title = stringResource(R.string.styles_overrides),
            addLabel = stringResource(R.string.styles_add_override),
            onAdd = { onAction(StylesAction.AddOverride) },
            modifier = Modifier.screenContent(widthClass),
        )
    }
    (state.editor as? StylesEditor.Override)?.let { form ->
        item(key = "override-editor") { OverrideEditorCard(form, onAction, Modifier.screenContent(widthClass)) }
    }
    if (state.overrides.isEmpty()) {
        item(key = "overrides-empty") { EmptyHint(stringResource(R.string.styles_overrides_empty), widthClass) }
    }
    items(state.overrides, key = { "override-${it.app.name}" }) { rule ->
        OverrideRow(rule, onAction, Modifier.screenContent(widthClass))
    }
}

private fun LazyListScope.exclusionSection(
    state: StylesUiState,
    onAction: (StylesAction) -> Unit,
    widthClass: WidthClass,
) {
    item(key = "excluded-header") {
        SectionHeaderWithAdd(
            title = stringResource(R.string.styles_excluded),
            addLabel = stringResource(R.string.styles_add_excluded),
            onAdd = { onAction(StylesAction.AddExclusion) },
            modifier = Modifier.screenContent(widthClass),
        )
    }
    (state.editor as? StylesEditor.Exclusion)?.let { form ->
        item(key = "excluded-editor") { ExclusionEditorCard(form, onAction, Modifier.screenContent(widthClass)) }
    }
    if (state.excluded.isEmpty()) {
        item(key = "excluded-empty") { EmptyHint(stringResource(R.string.styles_excluded_empty), widthClass) }
    }
    items(state.excluded, key = { "excluded-${it.app.name}" }) { rule ->
        ExcludedRow(rule, onAction, Modifier.screenContent(widthClass))
    }
}

@Composable
private fun SectionHeaderWithAdd(title: String, addLabel: String, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionHeader(text = title, modifier = Modifier.weight(1f))
        TextButton(onClick = onAdd) {
            Icon(painter = painterResource(TelltypeIcons.Add), contentDescription = null)
            Text(text = addLabel, modifier = Modifier.padding(start = Spacing.Small))
        }
    }
}

@Composable
private fun EmptyHint(text: String, widthClass: WidthClass, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .screenContent(widthClass)
            .padding(vertical = Spacing.Small),
    )
}
