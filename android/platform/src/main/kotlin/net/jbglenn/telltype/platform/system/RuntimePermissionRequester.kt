package net.jbglenn.telltype.platform.system

import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.domain.port.FixLauncher

/**
 * Asks for a runtime permission with the system dialog while one of the app's activities is in front (Setup, Home).
 * A service cannot show that dialog, so without one the fix opens the app's page in Settings. `:app` binds it.
 */
public interface RuntimePermissionRequester {
    /** Shows the system dialog for [fix]'s permission and returns true, or returns false when no activity can. */
    public suspend fun request(fix: FixTarget): Boolean
}

/** [FixLauncher] that tries the [requester] first for runtime permissions, then the Settings screens of [settings]. */
internal class RequestingFixLauncher(
    private val requester: RuntimePermissionRequester,
    private val settings: FixLauncher,
) : FixLauncher {
    override suspend fun open(fix: FixTarget) {
        val requested = fix in RUNTIME_PERMISSIONS && requester.request(fix)
        if (!requested) settings.open(fix)
    }

    private companion object {
        val RUNTIME_PERMISSIONS = setOf(
            FixTarget.MICROPHONE_PERMISSION,
            FixTarget.NOTIFICATION_PERMISSION,
            FixTarget.LOCAL_NETWORK_PERMISSION,
        )
    }
}
