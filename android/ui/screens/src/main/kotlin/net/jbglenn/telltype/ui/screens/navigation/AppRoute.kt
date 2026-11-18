package net.jbglenn.telltype.ui.screens.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.screens.R

/** Every destination of the app (type-safe Navigation routes). Routes carry ids only, never dictated text. */
@Serializable
sealed interface AppRoute {
    /** The five setup steps. */
    @Serializable
    data object Setup : AppRoute

    /** Home. */
    @Serializable
    data object Home : AppRoute

    /** History's list (with the detail pane from 840 dp). */
    @Serializable
    data object History : AppRoute

    /**
     * One History entry on its own page.
     *
     * @property id the session id.
     */
    @Serializable
    data class HistoryEntry(val id: String) : AppRoute

    /** Dictionary. */
    @Serializable
    data object Dictionary : AppRoute

    /** Snippets. */
    @Serializable
    data object Snippets : AppRoute

    /** Styles and excluded apps. */
    @Serializable
    data object Styles : AppRoute

    /** Settings. */
    @Serializable
    data object Settings : AppRoute

    /** Diagnostics. */
    @Serializable
    data object Diagnostics : AppRoute
}

/**
 * The destinations in the navigation bar or rail.
 *
 * @property route where it leads.
 * @property label its name.
 * @property icon its icon.
 */
internal enum class TopLevelDestination(
    val route: AppRoute,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    HOME(AppRoute.Home, R.string.nav_home, TelltypeIcons.Home),
    HISTORY(AppRoute.History, R.string.nav_history, TelltypeIcons.History),
    DICTIONARY(AppRoute.Dictionary, R.string.nav_dictionary, TelltypeIcons.Dictionary),
    SNIPPETS(AppRoute.Snippets, R.string.nav_snippets, TelltypeIcons.Snippet),
    SETTINGS(AppRoute.Settings, R.string.nav_settings, TelltypeIcons.Settings),
}
