package net.jbglenn.telltype.ui.screens.styles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.component.ContentListItem
import net.jbglenn.telltype.ui.designsystem.component.SelectableChip
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.EditorCard
import net.jbglenn.telltype.ui.screens.common.EditorField
import net.jbglenn.telltype.ui.screens.common.ToneChoices
import net.jbglenn.telltype.ui.screens.common.label

/** An app with its own category or tone. Tapping edits it. */
@Composable
internal fun OverrideRow(rule: AppRule, onAction: (StylesAction) -> Unit, modifier: Modifier = Modifier) {
    val parts = listOfNotNull(
        rule.categoryOverride?.let { stringResource(R.string.styles_override_category, stringResource(it.label)) },
        rule.toneOverride?.let { stringResource(R.string.styles_override_tone, stringResource(it.label)) },
    )
    ContentListItem(
        headline = rule.app.name,
        supporting = parts.joinToString(stringResource(R.string.list_separator)),
        icon = TelltypeIcons.Widgets,
        onClick = { onAction(StylesAction.EditOverride(rule.app)) },
        trailing = {
            IconButton(onClick = { onAction(StylesAction.RemoveOverride(rule.app)) }) {
                Icon(
                    painter = painterResource(TelltypeIcons.Delete),
                    contentDescription = stringResource(R.string.styles_remove_override, rule.app.name),
                )
            }
        },
        modifier = modifier,
    )
}

/** An excluded app, with the button that lets the bubble appear there again. */
@Composable
internal fun ExcludedRow(rule: AppRule, onAction: (StylesAction) -> Unit, modifier: Modifier = Modifier) {
    ContentListItem(
        headline = rule.app.name,
        icon = TelltypeIcons.Block,
        trailing = {
            IconButton(onClick = { onAction(StylesAction.RemoveExclusion(rule.app)) }) {
                Icon(
                    painter = painterResource(TelltypeIcons.Delete),
                    contentDescription = stringResource(R.string.styles_remove_excluded, rule.app.name),
                )
            }
        },
        modifier = modifier,
    )
}

/** The override form: package, category and tone as chips ("Keep" leaves it as shipped). */
@Composable
internal fun OverrideEditorCard(
    form: StylesEditor.Override,
    onAction: (StylesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditorCard(
        title = stringResource(R.string.styles_override_title),
        error = form.error?.let { stringResource(it) },
        onSave = { onAction(StylesAction.Save) },
        onCancel = { onAction(StylesAction.CancelEdit) },
        modifier = modifier,
    ) {
        EditorField(
            label = stringResource(R.string.styles_field_package),
            value = form.app,
            onValueChange = { onAction(StylesAction.AppChanged(it)) },
            isError = form.error == R.string.styles_error_package,
        )
        Text(text = stringResource(R.string.styles_field_category), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            SelectableChip(
                label = stringResource(R.string.styles_keep),
                selected = form.category == null,
                onClick = { onAction(StylesAction.CategoryChanged(null)) },
            )
            AppCategory.entries.forEach { category ->
                SelectableChip(
                    label = stringResource(category.label),
                    selected = form.category == category,
                    onClick = { onAction(StylesAction.CategoryChanged(category)) },
                )
            }
        }
        Text(text = stringResource(R.string.styles_field_tone), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            SelectableChip(
                label = stringResource(R.string.styles_keep),
                selected = form.tone == null,
                onClick = { onAction(StylesAction.ToneChanged(null)) },
            )
            ToneChoices.forEach { tone ->
                SelectableChip(
                    label = stringResource(tone.label),
                    selected = form.tone == tone,
                    onClick = { onAction(StylesAction.ToneChanged(tone)) },
                )
            }
        }
    }
}

/** The exclusion form: one package name. */
@Composable
internal fun ExclusionEditorCard(
    form: StylesEditor.Exclusion,
    onAction: (StylesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    EditorCard(
        title = stringResource(R.string.styles_excluded_title),
        error = form.error?.let { stringResource(it) },
        onSave = { onAction(StylesAction.Save) },
        onCancel = { onAction(StylesAction.CancelEdit) },
        modifier = modifier,
    ) {
        EditorField(
            label = stringResource(R.string.styles_field_package),
            supporting = stringResource(R.string.styles_field_package_help),
            value = form.app,
            onValueChange = { onAction(StylesAction.AppChanged(it)) },
            isError = form.error != null,
        )
    }
}
