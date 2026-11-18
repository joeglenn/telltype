package net.jbglenn.telltype.platform.system

import android.Manifest
import net.jbglenn.telltype.core.model.system.PermissionStatus

/** The individual checks behind a [PermissionStatus], one call each. Implemented over Android and by JVM fakes. */
internal interface PermissionProbe {
    /** Whether the runtime permission [permission] is granted. */
    fun granted(permission: String): Boolean

    /** Whether the app's notifications are enabled in Settings. */
    fun notificationsEnabled(): Boolean

    /** Whether the app's accessibility service is switched on and connected. */
    fun accessibilityServiceRunning(): Boolean

    /** Whether Advanced Protection is on. Only called on Android 16 and later. */
    fun advancedProtectionOn(): Boolean
}

/**
 * Reads a [PermissionStatus] (docs/PLAN.md section 7.6). Pure apart from [probe]; [sdkInt] decides which checks
 * exist: the local network permission from Android 17 (API 37; granted below, where it does not exist), Advanced
 * Protection from Android 16 (API 36; off below).
 */
internal class PermissionReader(private val sdkInt: Int, private val probe: PermissionProbe) {
    /** Every check, now. */
    fun read(): PermissionStatus = PermissionStatus(
        microphoneGranted = probe.granted(Manifest.permission.RECORD_AUDIO),
        accessibilityEnabled = probe.accessibilityServiceRunning(),
        notificationsGranted = probe.granted(Manifest.permission.POST_NOTIFICATIONS) && probe.notificationsEnabled(),
        localNetworkGranted = sdkInt < LOCAL_NETWORK_SDK || probe.granted(ACCESS_LOCAL_NETWORK),
        advancedProtectionOn = sdkInt >= ADVANCED_PROTECTION_SDK && probe.advancedProtectionOn(),
    )

    internal companion object {
        /** Android 17, where `ACCESS_LOCAL_NETWORK` exists. */
        const val LOCAL_NETWORK_SDK = 37

        /** Android 16, where `AdvancedProtectionManager` exists. */
        const val ADVANCED_PROTECTION_SDK = 36

        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, spelled out because the constant is API 37 only. */
        const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
