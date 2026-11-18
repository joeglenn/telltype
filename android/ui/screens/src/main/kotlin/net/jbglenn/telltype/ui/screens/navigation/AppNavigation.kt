package net.jbglenn.telltype.ui.screens.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy

/** The bottom navigation bar (phones). */
@Composable
internal fun AppNavigationBar(
    current: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = current.isIn(destination),
                onClick = { onSelect(destination) },
                icon = { Icon(painter = painterResource(destination.icon), contentDescription = null) },
                label = {
                    Text(text = stringResource(destination.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
            )
        }
    }
}

/** The navigation rail (600 dp and wider). */
@Composable
internal fun AppNavigationRail(
    current: NavDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = current.isIn(destination),
                onClick = { onSelect(destination) },
                icon = { Icon(painter = painterResource(destination.icon), contentDescription = null) },
                label = { Text(text = stringResource(destination.label)) },
            )
        }
    }
}

/** The bar item stays selected on the pages below it (History's entries under History, and so on). */
private fun NavDestination?.isIn(destination: TopLevelDestination): Boolean {
    val owners = when (destination) {
        TopLevelDestination.HOME -> listOf(AppRoute.Home::class)

        TopLevelDestination.HISTORY -> listOf(AppRoute.History::class, AppRoute.HistoryEntry::class)

        TopLevelDestination.DICTIONARY -> listOf(AppRoute.Dictionary::class)

        TopLevelDestination.SNIPPETS -> listOf(AppRoute.Snippets::class)

        TopLevelDestination.SETTINGS -> listOf(
            AppRoute.Settings::class,
            AppRoute.Styles::class,
            AppRoute.Diagnostics::class,
        )
    }
    return this?.hierarchy?.any { node -> owners.any { node.hasRoute(it) } } == true
}
