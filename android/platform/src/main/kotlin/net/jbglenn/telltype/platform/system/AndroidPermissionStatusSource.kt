package net.jbglenn.telltype.platform.system

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.advancedprotection.AdvancedProtectionManager
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.system.PermissionStatus
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.service.TelltypeAccessibilityService

/**
 * [PermissionStatusSource] over Android. [refresh] re-reads every check on the I/O dispatcher; the status is also
 * refreshed by itself whenever the accessibility service connects or disconnects.
 */
internal class AndroidPermissionStatusSource(
    private val reader: PermissionReader,
    bridge: AccessibilityServiceBridge,
    private val dispatchers: DispatcherProvider,
    scope: CoroutineScope,
) : PermissionStatusSource {
    private val state = MutableStateFlow(UNKNOWN)

    override val status: StateFlow<PermissionStatus> = state.asStateFlow()

    init {
        scope.launch(dispatchers.default) { bridge.connected.collect { refresh() } }
    }

    override suspend fun refresh(): PermissionStatus =
        withContext(dispatchers.io) { reader.read() }.also { state.value = it }

    private companion object {
        /** Before the first reading: nothing assumed granted. */
        val UNKNOWN = PermissionStatus(
            microphoneGranted = false,
            accessibilityEnabled = false,
            notificationsGranted = false,
            localNetworkGranted = false,
            advancedProtectionOn = false,
        )
    }
}

/** [PermissionProbe] over the framework. */
internal class AndroidPermissionProbe(
    private val context: Context,
    private val bridge: AccessibilityServiceBridge,
    private val logger: Logger,
) : PermissionProbe {
    override fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun notificationsEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false

    override fun accessibilityServiceRunning(): Boolean {
        val ours = ComponentName(context, TelltypeAccessibilityService::class.java)
        val enabled = context.getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
            .any { it.resolveInfo.serviceInfo.let { info -> ComponentName(info.packageName, info.name) } == ours }
        return enabled && bridge.connected.value
    }

    override fun advancedProtectionOn(): Boolean =
        if (Build.VERSION.SDK_INT >= PermissionReader.ADVANCED_PROTECTION_SDK) advancedProtection() else false

    @RequiresApi(PermissionReader.ADVANCED_PROTECTION_SDK)
    private fun advancedProtection(): Boolean = try {
        context.getSystemService(AdvancedProtectionManager::class.java)?.isAdvancedProtectionEnabled ?: false
    } catch (e: SecurityException) {
        logger.warn(TAG, e) { "Advanced Protection state not readable" }
        false
    }

    private companion object {
        val TAG = LogTag("Permissions")
    }
}
