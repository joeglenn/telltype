package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/** The title above a group of rows. Announced as a heading, so screen-reader users can jump between groups. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .semantics { heading() }
            .padding(top = Spacing.ExtraLarge, bottom = Spacing.Small),
    )
}

@Preview
@Composable
private fun SectionHeaderPreview() {
    TelltypePreviewTheme { SectionHeader(text = "Bubble") }
}
