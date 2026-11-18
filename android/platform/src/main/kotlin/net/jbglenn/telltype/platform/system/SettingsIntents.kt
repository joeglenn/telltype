package net.jbglenn.telltype.platform.system

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import net.jbglenn.telltype.core.model.error.FixTarget

/**
 * The screens a Fix action opens (docs/PLAN.md sections 5.3, 7.6 and 10). Every intent starts a new task. The app's
 * own setup screens are reached through its launch intent with [EXTRA_FIX_TARGET] set to the [FixTarget] name.
 */
public object SettingsIntents {
    /** Extra of the app's launch intent: the [FixTarget] name whose setup step to open. */
    public const val EXTRA_FIX_TARGET: String = "net.jbglenn.telltype.extra.FIX_TARGET"

    /**
     * The screen for [fix] in the app [packageName], whose launch intent is [launch] (null when it has none).
     * A service cannot show the runtime permission dialog, so runtime permissions and server setup open the app
     * itself ([launch] with [EXTRA_FIX_TARGET]; its activity asks with the system dialog or shows the setup step),
     * brought to the front with the request even when it is already open. Without a launch intent, [systemScreen].
     */
    public fun forFix(fix: FixTarget, packageName: String, launch: Intent?): Intent = when (fix) {
        FixTarget.MICROPHONE_PERMISSION,
        FixTarget.NOTIFICATION_PERMISSION,
        FixTarget.LOCAL_NETWORK_PERMISSION,
        FixTarget.SERVER_SETUP,
        -> (launch?.let(::Intent)?.addFlags(TO_FRONT) ?: systemScreen(fix, packageName))
            .putExtra(EXTRA_FIX_TARGET, fix.name)

        FixTarget.ACCESSIBILITY_SERVICE, FixTarget.STORAGE -> systemScreen(fix, packageName)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The system Settings screen for [fix] of the app [packageName]: where a permission is switched on once the system
     * no longer shows its dialog (denied twice), or what a fix opens when the app has no launch intent.
     */
    public fun systemScreen(fix: FixTarget, packageName: String): Intent = when (fix) {
        FixTarget.MICROPHONE_PERMISSION, FixTarget.LOCAL_NETWORK_PERMISSION, FixTarget.SERVER_SETUP ->
            appDetails(packageName)

        FixTarget.ACCESSIBILITY_SERVICE -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        FixTarget.NOTIFICATION_PERMISSION ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

        FixTarget.STORAGE -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The app's App info page, which also holds "Allow restricted settings" (Android 13 and later, section 7.6):
     * setup sends the user here before the accessibility switch of a sideloaded app can be turned on.
     */
    public fun appDetails(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The Settings home, when a more specific screen does not exist on this device. */
    public fun fallback(): Intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** An open activity of the app receives the request (`onNewIntent`) instead of the task only coming forward. */
    private const val TO_FRONT = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
}
