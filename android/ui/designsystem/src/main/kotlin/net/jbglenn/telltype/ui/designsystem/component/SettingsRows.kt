package net.jbglenn.telltype.ui.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.jbglenn.telltype.ui.designsystem.Layout
import net.jbglenn.telltype.ui.designsystem.R
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

private val TransparentRow: Color = Color.Transparent

/** A setting that is on or off. The whole row is the touch target and is announced as a switch. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = supporting?.let { { Text(text = it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = TransparentRow),
        modifier = modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/** A row that opens another screen ([external] false, chevron) or a system screen ([external] true). */
@Composable
fun LinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    @DrawableRes icon: Int? = null,
    external: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = supporting?.let { { Text(text = it) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = {
            Icon(
                painter = painterResource(if (external) TelltypeIcons.OpenInNew else TelltypeIcons.ChevronRight),
                contentDescription = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = TransparentRow),
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/**
 * A row that does something at once or after a question, such as "Clear history now"; [destructive] colours its
 * title like the error colour. Without [enabled] it shows but cannot be pressed.
 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val titleColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = ActionRowTokens.DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = { Text(text = title, color = titleColor) },
        supportingContent = supporting?.let { { Text(text = it) } },
        colors = ListItemDefaults.colors(containerColor = TransparentRow),
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

/** [ActionRow]'s look. */
internal object ActionRowTokens {
    /** Material's opacity for disabled content. */
    const val DISABLED_ALPHA = 0.38f
}

/** A value that is shown, not edited here. */
@Composable
fun InfoRow(title: String, value: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = { Text(text = value) },
        leadingContent = icon?.let { { RowIcon(it) } },
        colors = ListItemDefaults.colors(containerColor = TransparentRow),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
    )
}

/**
 * The labels of a [ChoiceRow]'s options, in order.
 *
 * @property labels one label per option.
 */
@Immutable
data class ChoiceOptions(val labels: List<String>)

/**
 * A setting with a few fixed values. Shows the current value; tapping expands the options inline as radio
 * buttons (no dialog, and every option stays readable at large font scales). While not [enabled] it is dimmed and
 * does not open.
 */
@Composable
fun ChoiceRow(
    title: String,
    options: ChoiceOptions,
    selectedIndex: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    val current = options.labels.getOrNull(selectedIndex).orEmpty()
    val toggleLabel = stringResource(if (expanded) R.string.ds_collapse else R.string.ds_expand)
    val textColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = ActionRowTokens.DISABLED_ALPHA)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(text = title, color = textColor) },
            supportingContent = { Text(text = current, color = textColor) },
            leadingContent = icon?.let { { RowIcon(it) } },
            trailingContent = {
                Icon(
                    painter = painterResource(TelltypeIcons.ExpandMore),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) ChevronDefaults.HALF_TURN else 0f),
                )
            },
            colors = ListItemDefaults.colors(containerColor = TransparentRow),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = toggleLabel) {
                    onExpandedChange(!expanded)
                },
        )
        AnimatedVisibility(visible = expanded && enabled) {
            ChoiceOptionList(options = options, selectedIndex = selectedIndex, onSelect = onSelect)
        }
    }
}

@Composable
private fun ChoiceOptionList(options: ChoiceOptions, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val selected = stringResource(R.string.ds_selected)
    val notSelected = stringResource(R.string.ds_not_selected)
    Column(modifier = Modifier.selectableGroup()) {
        options.labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Layout.MinTouchTarget)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { stateDescription = if (isSelected) selected else notSelected }
                    .padding(start = OptionIndent, end = Spacing.Large),
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun RowIcon(@DrawableRes icon: Int) {
    Icon(painter = painterResource(icon), contentDescription = null)
}

private val OptionIndent = 56.dp

@Preview
@Composable
private fun SettingsRowsPreview() {
    TelltypePreviewTheme {
        Column {
            SwitchRow(title = "Show the bubble", checked = true, onCheckedChange = {}, icon = TelltypeIcons.Bubble)
            LinkRow(title = "Server", supporting = "192.0.2.10:8170", onClick = {}, icon = TelltypeIcons.Dns)
            ChoiceRow(
                title = "Keep history",
                options = ChoiceOptions(listOf("Off", "7 days", "30 days")),
                selectedIndex = 2,
                expanded = true,
                onExpandedChange = {},
                onSelect = {},
            )
        }
    }
}

/** Fixed values used in this file. */
private object ChevronDefaults {
    const val HALF_TURN = 180f
}
