package net.jbglenn.telltype.ui.screens.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import net.jbglenn.telltype.ui.designsystem.WidthClass

/**
 * The whole app: the navigation bar (phones) or rail (600 dp and wider) around one `NavHost` with every screen.
 * Setup is full screen. `:app` mounts this in its activity with a [viewModelFactory] from Hilt
 * (`ScreenViewModelFactory`).
 *
 * @param startAtSetup start with setup (nothing configured yet) instead of Home.
 * @param showDebugOptions debug builds: offer the fake engines in Settings.
 */
@Composable
fun TelltypeNavHost(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
    startAtSetup: Boolean = false,
    showDebugOptions: Boolean = false,
    navController: NavHostController = rememberNavController(),
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthClass = WidthClass.of(maxWidth)
        val backStackEntry by navController.currentBackStackEntryAsState()
        val current = backStackEntry?.destination
        val showNavigation = current != null && !current.hasRoute(AppRoute.Setup::class)
        val onSelect: (TopLevelDestination) -> Unit = { navController.navigateTopLevel(it.route) }
        val graph = AppGraph(
            navController = navController,
            factory = viewModelFactory,
            widthClass = widthClass,
            showDebugOptions = showDebugOptions,
        )
        val start: AppRoute = if (startAtSetup) AppRoute.Setup else AppRoute.Home
        if (widthClass == WidthClass.COMPACT) {
            Scaffold(
                bottomBar = { if (showNavigation) AppNavigationBar(current, onSelect) },
                contentWindowInsets = WindowInsets(0),
            ) { insets ->
                AppNavGraph(
                    graph = graph,
                    start = start,
                    modifier = Modifier.padding(insets).consumeWindowInsets(insets),
                )
            }
        } else {
            Row {
                if (showNavigation) AppNavigationRail(current, onSelect)
                AppNavGraph(graph = graph, start = start, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Goes to a top-level destination, keeping one copy of each and restoring its state. It pops up to Home, the
 * bottom of the stack whenever the bar is shown, not to the graph's start destination: after first-run Setup (the
 * start destination) removed itself, that one is no longer on the stack, and popping to it did nothing but log
 * "Ignoring popBackStack" and let the stack grow.
 */
internal fun NavHostController.navigateTopLevel(route: AppRoute) {
    navigate(route) {
        popUpTo<AppRoute.Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
