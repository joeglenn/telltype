package net.jbglenn.telltype.ui.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/**
 * A list row with a headline and supporting text (History entries, dictionary terms, snippets). Clickable when
 * [onClick] is set; [selected] highlights the row shown in the detail pane.
 */
@Composable
fun ContentListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    overline: String? = null,
    headlineMaxLines: Int = 2,
    @DrawableRes icon: Int? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    ListItem(
        headlineContent = { Text(text = headline, maxLines = headlineMaxLines, overflow = TextOverflow.Ellipsis) },
        overlineContent = overline?.let { { Text(text = it) } },
        supportingContent = supporting?.let { { Text(text = it) } },
        leadingContent = icon?.let { { Icon(painter = painterResource(it), contentDescription = null) } },
        trailingContent = trailing,
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors(containerColor = Color.Transparent)
        },
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier),
    )
}

@Preview
@Composable
private fun ContentListItemPreview() {
    TelltypePreviewTheme {
        ContentListItem(
            headline = "Can you send me the file before the meeting?",
            supporting = "Messages · 09:12 · 3 s",
            icon = TelltypeIcons.History,
            onClick = {},
        )
    }
}
