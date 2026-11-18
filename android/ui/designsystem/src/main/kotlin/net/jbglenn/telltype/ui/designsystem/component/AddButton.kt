package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/**
 * The extended floating action button that adds an item ("Add word", "Add snippet"). Material 3 1.4 clears the
 * semantics of the button's content, so a screen reader would hear only "button"; the label is set as the
 * button's content description here.
 */
@Composable
fun AddButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(painter = painterResource(TelltypeIcons.Add), contentDescription = null) },
        text = { Text(text = label) },
        modifier = modifier.semantics { contentDescription = label },
    )
}

@Preview
@Composable
private fun AddButtonPreview() {
    TelltypePreviewTheme { AddButton(label = "Add word", onClick = {}) }
}
