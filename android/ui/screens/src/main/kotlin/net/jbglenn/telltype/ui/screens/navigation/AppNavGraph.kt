package net.jbglenn.telltype.ui.screens.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.ui.screens.diagnostics.DiagnosticsRoute
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryRoute
import net.jbglenn.telltype.ui.screens.history.HistoryDetailRoute
import net.jbglenn.telltype.ui.screens.history.HistoryRoute
import net.jbglenn.telltype.ui.screens.home.HomeRoute
import net.jbglenn.telltype.ui.screens.settings.SettingsDestination
import net.jbglenn.telltype.ui.screens.settings.SettingsRoute
import net.jbglenn.telltype.ui.screens.setup.SetupRoute
import net.jbglenn.telltype.ui.screens.snippets.SnippetsRoute
import net.jbglenn.telltype.ui.screens.styles.StylesRoute

/** The destinations and the moves between them. */
@Composable
internal fun AppNavGraph(graph: AppGraph, start: AppRoute, modifier: Modifier = Modifier) {
    NavHost(navController = graph.navController, startDestination = start, modifier = modifier) {
        setupAndHome(graph)
        history(graph)
        lists(graph)
        settings(graph)
    }
}

private fun NavGraphBuilder.setupAndHome(graph: AppGraph) {
    val nav = graph.navController
    composable<AppRoute.Setup> {
        SetupRoute(
            factory = graph.factory,
            widthClass = graph.widthClass,
            onFinish = {
                nav.navigate(AppRoute.Home) {
                    popUpTo<AppRoute.Setup> { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable<AppRoute.Home> {
        HomeRoute(
            factory = graph.factory,
            widthClass = graph.widthClass,
            onOpenSetup = { nav.navigate(AppRoute.Setup) },
            onOpenEntry = { nav.navigate(AppRoute.HistoryEntry(it.value)) },
            onOpenHistory = { nav.navigateTopLevel(AppRoute.History) },
        )
    }
}

private fun NavGraphBuilder.history(graph: AppGraph) {
    val nav = graph.navController
    composable<AppRoute.History> {
        HistoryRoute(
            factory = graph.factory,
            widthClass = graph.widthClass,
            onOpenEntry = { nav.navigate(AppRoute.HistoryEntry(it.value)) },
            onOpenSettings = { nav.navigateTopLevel(AppRoute.Settings) },
        )
    }
    composable<AppRoute.HistoryEntry> { entry ->
        HistoryDetailRoute(
            factory = graph.factory,
            id = SessionId(entry.toRoute<AppRoute.HistoryEntry>().id),
            widthClass = graph.widthClass,
            onNavigateUp = { nav.navigateUp() },
        )
    }
}

private fun NavGraphBuilder.lists(graph: AppGraph) {
    composable<AppRoute.Dictionary> { DictionaryRoute(factory = graph.factory, widthClass = graph.widthClass) }
    composable<AppRoute.Snippets> { SnippetsRoute(factory = graph.factory, widthClass = graph.widthClass) }
}

private fun NavGraphBuilder.settings(graph: AppGraph) {
    val nav = graph.navController
    composable<AppRoute.Settings> {
        SettingsRoute(
            factory = graph.factory,
            widthClass = graph.widthClass,
            showDebugOptions = graph.showDebugOptions,
            onNavigate = { destination ->
                nav.navigate(
                    when (destination) {
                        SettingsDestination.SERVER -> AppRoute.Setup
                        SettingsDestination.STYLES -> AppRoute.Styles
                        SettingsDestination.DIAGNOSTICS -> AppRoute.Diagnostics
                    },
                )
            },
        )
    }
    composable<AppRoute.Styles> {
        StylesRoute(factory = graph.factory, widthClass = graph.widthClass, onNavigateUp = { nav.navigateUp() })
    }
    composable<AppRoute.Diagnostics> {
        DiagnosticsRoute(factory = graph.factory, widthClass = graph.widthClass, onNavigateUp = { nav.navigateUp() })
    }
}
