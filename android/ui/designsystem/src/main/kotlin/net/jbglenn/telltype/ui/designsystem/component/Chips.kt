package net.jbglenn.telltype.ui.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/** A chip that does something (Copy, Re-run cleanup). 32 dp high inside a 48 dp touch target. */
@Composable
fun ActionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    AssistChip(
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = icon?.let {
            {
                Icon(
                    painter = painterResource(it),
                    contentDescription = null,
                    Modifier.size(AssistChipDefaults.IconSize),
                )
            }
        },
        modifier = modifier,
    )
}

/** A chip that is on or off (a filter or a choice). The check mark pairs the colour change with a shape. */
@Composable
fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    painter = painterResource(TelltypeIcons.Check),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        } else {
            null
        },
        modifier = modifier,
    )
}

/** A small read-only label (a sounds-like form, "Learned"). Not interactive. */
@Composable
fun Tag(label: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = Spacing.Small, vertical = Spacing.ExtraSmall),
        )
    }
}

@Preview
@Composable
private fun ChipsPreview() {
    TelltypePreviewTheme {
        androidx.compose.foundation.layout.Row {
            ActionChip(label = "Copy", onClick = {}, icon = TelltypeIcons.Copy)
            SelectableChip(label = "Errors only", selected = true, onClick = {})
            Tag(label = "Learned")
        }
    }
}
