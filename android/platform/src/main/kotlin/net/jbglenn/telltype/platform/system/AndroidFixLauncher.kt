package net.jbglenn.telltype.platform.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * [FixLauncher]: opens the screen of [SettingsIntents.forFix]; when the device lacks it, the App info page, then the
 * Settings home. Started from the accessibility service's context when connected (its binding may start activities
 * from the background), else from the application context.
 */
public class AndroidFixLauncher internal constructor(
    private val context: Context,
    private val bridge: AccessibilityServiceBridge,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : FixLauncher {
    override suspend fun open(fix: FixTarget) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        start(SettingsIntents.forFix(fix, context.packageName, launch))
    }

    /**
     * Opens the system Settings screen for [fix] ([SettingsIntents.systemScreen]): the app's activity calls it when
     * the system no longer shows the permission dialog.
     */
    public suspend fun openSystemScreen(fix: FixTarget) {
        start(SettingsIntents.systemScreen(fix, context.packageName))
    }

    /** Opens the app's App info page, where "Allow restricted settings" is (setup's walk-through). */
    public suspend fun openRestrictedSettingsInfo() {
        start(SettingsIntents.appDetails(context.packageName))
    }

    private suspend fun start(intent: Intent) {
        withContext(dispatchers.main) {
            val starter = bridge.service.value ?: context
            val candidates = listOf(intent, SettingsIntents.appDetails(context.packageName), SettingsIntents.fallback())
            candidates.firstOrNull { candidate -> tryStart(starter, candidate) }
        }
    }

    private fun tryStart(starter: Context, intent: Intent): Boolean = try {
        starter.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        logger.warn(TAG, e) { "no activity for ${intent.action}" }
        false
    }

    private companion object {
        val TAG = LogTag("FixLauncher")
    }
}
