package net.jbglenn.telltype.permissions

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.platform.system.AndroidFixLauncher
import net.jbglenn.telltype.platform.system.RuntimePermissionRequester

/**
 * Shows the system permission dialog through [MainActivity][net.jbglenn.telltype.MainActivity] while it is
 * resumed. After the answer the permission status is refreshed; a permission the system no longer asks for (denied
 * twice) opens its system Settings screen instead (App info, or the app's notification settings). Holds the
 * activity's launcher only between resume and pause.
 */
@Singleton
internal class ActivityPermissionRequester @Inject constructor(
    private val permissions: PermissionStatusSource,
    private val settings: AndroidFixLauncher,
    private val dispatchers: DispatcherProvider,
) : RuntimePermissionRequester {
    private var active: Registration? = null

    /** Registers the dialog for [activity]; call from its `onCreate`. */
    fun register(activity: ComponentActivity) {
        lateinit var registration: Registration
        val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val fix = registration.pending
            activity.lifecycleScope.launch {
                permissions.refresh()
                val askable = permissionFor(fix)?.let(activity::shouldShowRequestPermissionRationale) ?: true
                if (!granted && !askable && fix != null) settings.openSystemScreen(fix)
            }
        }
        registration = Registration(launcher)
        activity.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    active = registration
                }

                override fun onPause(owner: LifecycleOwner) {
                    if (active === registration) active = null
                }
            },
        )
    }

    override suspend fun request(fix: FixTarget): Boolean = withContext(dispatchers.main) { requestNow(fix) }

    /**
     * [request] on the main thread: shows the dialog for [fix]'s permission while the activity is resumed and returns
     * true, or returns false (no such permission on this device, or no resumed activity).
     */
    fun requestNow(fix: FixTarget): Boolean {
        val permission = permissionFor(fix)
        val current = active
        return if (permission == null || current == null) {
            false
        } else {
            current.pending = fix
            current.launcher.launch(permission)
            true
        }
    }

    /** One activity's dialog launcher and the fix it was last launched for. */
    private class Registration(val launcher: ActivityResultLauncher<String>) {
        var pending: FixTarget? = null
    }

    private fun permissionFor(fix: FixTarget?): String? = when (fix) {
        FixTarget.MICROPHONE_PERMISSION -> Manifest.permission.RECORD_AUDIO

        FixTarget.NOTIFICATION_PERMISSION -> Manifest.permission.POST_NOTIFICATIONS

        FixTarget.LOCAL_NETWORK_PERMISSION -> if (Build.VERSION.SDK_INT >
            LAST_WITHOUT_LOCAL_NETWORK
        ) {
            LOCAL_NETWORK
        } else {
            null
        }

        else -> null
    }

    private companion object {
        /** Android 16 (API 36) has no local network permission yet; it arrives with Android 17. */
        const val LAST_WITHOUT_LOCAL_NETWORK = 36
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
