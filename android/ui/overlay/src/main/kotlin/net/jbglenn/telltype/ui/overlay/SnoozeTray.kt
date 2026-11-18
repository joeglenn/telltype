package net.jbglenn.telltype.ui.overlay

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons

/**
 * The band shown at the bottom of the screen while the bubble is dragged: three drop targets, the one under the
 * bubble highlighted with a filled container and a thicker label (not colour alone).
 *
 * The band is at least [BubbleTokens.SnoozeZoneHeight] high and grows with its labels (at font scale 2.0 they
 * wrap to three lines); the three targets always share the height of the tallest. Its host must therefore give it
 * unbounded (wrap-content) height and pass the measured height to [BubblePlacement.snoozeTargetAt] as the zone
 * height: a window fixed at [BubbleTokens.SnoozeZoneHeight] would clip the labels.
 *
 * @param highlighted the target under the bubble now, or null.
 */
@Composable
fun SnoozeTray(highlighted: SnoozeTarget?, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BubbleTokens.SnoozeZoneHeight)
            .height(IntrinsicSize.Min)
            .padding(Spacing.Large),
    ) {
        SnoozeTargetBox(
            icon = TelltypeIcons.Snooze,
            label = stringResource(R.string.bubble_snooze_hour),
            active = highlighted == SnoozeTarget.ONE_HOUR,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        SnoozeTargetBox(
            icon = TelltypeIcons.Schedule,
            label = stringResource(R.string.bubble_snooze_app),
            active = highlighted == SnoozeTarget.THIS_APP,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        SnoozeTargetBox(
            icon = TelltypeIcons.Block,
            label = stringResource(R.string.bubble_action_never_here),
            active = highlighted == SnoozeTarget.NEVER_IN_THIS_APP,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

@Composable
private fun SnoozeTargetBox(@DrawableRes icon: Int, label: String, active: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (active) colors.inverseSurface else colors.surfaceContainerHigh,
        contentColor = if (active) colors.inverseOnSurface else colors.onSurface,
        shadowElevation = BubbleTokens.Elevation,
        modifier = modifier.semantics { selected = active },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Small, Alignment.CenterVertically),
            modifier = Modifier.padding(horizontal = Spacing.Small, vertical = Spacing.Large),
        ) {
            Icon(painter = painterResource(icon), contentDescription = null)
            Text(
                text = label,
                style = if (active) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
