package net.jbglenn.telltype.ui.screens.navigation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import net.jbglenn.telltype.ui.designsystem.WidthClass

/**
 * What every destination needs.
 *
 * @property navController the controller.
 * @property factory creates the ViewModels.
 * @property widthClass the window width class.
 * @property showDebugOptions debug builds.
 */
@Immutable
internal class AppGraph(
    val navController: NavHostController,
    val factory: ViewModelProvider.Factory,
    val widthClass: WidthClass,
    val showDebugOptions: Boolean,
)
