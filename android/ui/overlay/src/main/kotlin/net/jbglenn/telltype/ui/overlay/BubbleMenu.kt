package net.jbglenn.telltype.ui.overlay

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons

/**
 * The long-press menu above the bubble (docs/PLAN.md section 5.2): "Hide for 1 hour", "Never show in this app" and
 * "Close". It is drawn inside the bubble's own window rather than as a popup, because that window is not focusable
 * and sizes itself to the bubble. Screen readers hear it as a pane.
 */
@Composable
internal fun BubbleMenu(onEvent: (BubbleEvent) -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.bubble_menu_title)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = BubbleTokens.Elevation,
        modifier = modifier
            .width(IntrinsicSize.Max)
            .semantics { paneTitle = title },
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.Small)) {
            MenuItem(TelltypeIcons.Snooze, stringResource(R.string.bubble_action_hide_hour)) {
                onEvent(BubbleEvent.SnoozeRequested)
            }
            MenuItem(TelltypeIcons.Block, stringResource(R.string.bubble_action_never_here)) {
                onEvent(BubbleEvent.NeverShowInThisApp)
            }
            MenuItem(TelltypeIcons.Close, stringResource(R.string.bubble_menu_close)) {
                onEvent(BubbleEvent.MenuDismissed)
            }
        }
    }
}

@Composable
private fun MenuItem(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BubbleTokens.TouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.Large, vertical = Spacing.Small),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
    }
}
