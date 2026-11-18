package net.jbglenn.telltype.ui.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.jbglenn.telltype.ui.designsystem.Layout
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import net.jbglenn.telltype.ui.designsystem.warningColors

/**
 * A summary card: an icon and title that say the overall state, an optional line of detail, and optional rows
 * (for example one [StatusProblemRow] per problem). The title is a polite live region, so a change of state is
 * announced.
 */
@Composable
fun StatusCard(
    title: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    body: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val (container, onContainer) = when (tone) {
        StatusTone.GOOD -> colors.tertiaryContainer to colors.onTertiaryContainer
        StatusTone.ATTENTION -> colors.warningColors().let { it.container to it.content }
        StatusTone.PROBLEM -> colors.errorContainer to colors.onErrorContainer
        StatusTone.PAUSED -> colors.surfaceContainerHighest to colors.onSurface
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Large),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            StatusTitle(title = title, icon = tone.icon, iconTint = onContainer)
            if (body != null) {
                Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
            content()
        }
    }
}

@Composable
private fun StatusTitle(title: String, @DrawableRes icon: Int, iconTint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = iconTint)
        Text(text = title, style = MaterialTheme.typography.titleMedium)
    }
}

private val StatusTone.icon: Int
    get() = when (this) {
        StatusTone.GOOD -> TelltypeIcons.CheckCircle
        StatusTone.ATTENTION -> TelltypeIcons.Info
        StatusTone.PROBLEM -> TelltypeIcons.Error
        StatusTone.PAUSED -> TelltypeIcons.Snooze
    }

/**
 * One problem inside a [StatusCard]: what is wrong and the button that fixes it. From font scale 1.5 the button
 * goes under the message, which would otherwise be squeezed into a column a few letters wide.
 */
@Composable
fun StatusProblemRow(message: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val stacked = LocalDensity.current.fontScale >= StatusCardDefaults.STACKED_FONT_SCALE
    val button = @Composable { FilledTonalButton(onClick = onAction) { Text(text = actionLabel) } }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Layout.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        ) {
            Icon(
                painter = painterResource(TelltypeIcons.Warning),
                contentDescription = null,
                modifier = Modifier.size(StatusCardDefaults.ProblemIconSize),
            )
            Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!stacked) button()
        }
        if (stacked) {
            Box(modifier = Modifier.padding(start = StatusCardDefaults.ProblemIconSize + Spacing.Medium)) { button() }
        }
    }
}

/** Fixed values of the status card. */
private object StatusCardDefaults {
    /** The font scale from which a problem's button goes under its message. */
    const val STACKED_FONT_SCALE = 1.5f

    /** Size of the warning mark in front of each problem. */
    val ProblemIconSize = 20.dp
}

@Preview
@Composable
private fun StatusCardPreview() {
    TelltypePreviewTheme {
        StatusCard(title = "2 things need attention", tone = StatusTone.PROBLEM) {
            StatusProblemRow(message = "Microphone needed", actionLabel = "Fix", onAction = {})
        }
    }
}
