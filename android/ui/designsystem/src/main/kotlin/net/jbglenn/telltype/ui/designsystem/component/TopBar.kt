package net.jbglenn.telltype.ui.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.ui.designsystem.R
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme

/** The screen's title bar, with Back when [onNavigateUp] is set and optional actions. */
@OptIn(ExperimentalMaterial3Api::class) // TopAppBar is still experimental in Material 3 1.4.
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onNavigateUp != null) {
                IconButton(onClick = onNavigateUp) {
                    Icon(
                        painter = painterResource(TelltypeIcons.ArrowBack),
                        contentDescription = stringResource(R.string.ds_navigate_up),
                    )
                }
            }
        },
        actions = actions,
        modifier = modifier,
    )
}

@Preview
@Composable
private fun TopBarPreview() {
    TelltypePreviewTheme { TopBar(title = "History", onNavigateUp = {}) }
}
