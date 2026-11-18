package net.jbglenn.telltype

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.permissions.ActivityPermissionRequester
import net.jbglenn.telltype.platform.system.SettingsIntents
import net.jbglenn.telltype.ui.designsystem.TelltypeTheme
import net.jbglenn.telltype.ui.screens.common.ScreenViewModelFactory
import net.jbglenn.telltype.ui.screens.navigation.AppRoute
import net.jbglenn.telltype.ui.screens.navigation.TelltypeNavHost

/**
 * The single activity (Appendix F): the app's screens, edge to edge, starting at Setup until the server is configured
 * (or the fake engines are on). A launch intent with `SettingsIntents.EXTRA_FIX_TARGET` (from a bubble's Fix chip or
 * a notification) opens the screen that fixes it and, for a runtime permission, asks for it with the system dialog
 * once the activity is resumed (the bubble's service cannot show that dialog). Also the accessibility service's
 * settings activity.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    internal lateinit var viewModelFactory: ScreenViewModelFactory

    @Inject
    internal lateinit var settings: SettingsRepository

    @Inject
    internal lateinit var permissionRequester: ActivityPermissionRequester

    private val fixTarget = MutableStateFlow<FixTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        permissionRequester.register(this)
        if (savedInstanceState == null) intent.fixTarget()?.let(::openFix)
        setContent {
            TelltypeTheme {
                AppScreens(settings, viewModelFactory, fixTarget) { fixTarget.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.fixTarget()?.let(::openFix)
    }

    /** Shows the screen for [fix] and, when it is a runtime permission, the system dialog as soon as resumed. */
    private fun openFix(fix: FixTarget) {
        fixTarget.value = fix
        lifecycleScope.launch { lifecycle.withResumed { permissionRequester.requestNow(fix) } }
    }

    private fun Intent.fixTarget(): FixTarget? = getStringExtra(SettingsIntents.EXTRA_FIX_TARGET)?.let { name ->
        FixTarget.entries.firstOrNull { it.name == name }
    }
}

/** The navigation host once the settings are read; then follows [fixTarget] requests. */
@Composable
private fun AppScreens(
    settings: SettingsRepository,
    factory: ScreenViewModelFactory,
    fixTarget: StateFlow<FixTarget?>,
    onFixOpen: () -> Unit,
) {
    val startAtSetup by produceState<Boolean?>(initialValue = null, settings) {
        val current = settings.current()
        value = !current.server.isConfigured && !current.advanced.useFakeEngines
    }
    val start = startAtSetup ?: return
    val navController = rememberNavController()
    TelltypeNavHost(
        viewModelFactory = factory,
        startAtSetup = start,
        showDebugOptions = BuildConfig.DEBUG,
        navController = navController,
    )
    val fix by fixTarget.collectAsState()
    val currentOnFixOpen by rememberUpdatedState(onFixOpen)
    LaunchedEffect(fix) {
        fix?.let { target ->
            navController.navigate(routeFor(target)) { launchSingleTop = true }
            currentOnFixOpen()
        }
    }
}

/** Server and permission problems are fixed in Setup; storage problems in Settings (history and retention). */
internal fun routeFor(target: FixTarget): AppRoute = when (target) {
    FixTarget.MICROPHONE_PERMISSION,
    FixTarget.ACCESSIBILITY_SERVICE,
    FixTarget.NOTIFICATION_PERMISSION,
    FixTarget.LOCAL_NETWORK_PERMISSION,
    FixTarget.SERVER_SETUP,
    -> AppRoute.Setup

    FixTarget.STORAGE -> AppRoute.Settings
}
