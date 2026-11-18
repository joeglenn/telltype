package net.jbglenn.telltype.platform.system

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIntentsTest {
    private val app = "org.example.app"
    private val launch = Intent(Intent.ACTION_MAIN).setClassName(app, "$app.MainActivity")

    @Test
    fun `each fix opens its screen in a new task`() {
        val expected = mapOf(
            FixTarget.MICROPHONE_PERMISSION to Intent.ACTION_MAIN,
            FixTarget.LOCAL_NETWORK_PERMISSION to Intent.ACTION_MAIN,
            FixTarget.ACCESSIBILITY_SERVICE to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            FixTarget.NOTIFICATION_PERMISSION to Intent.ACTION_MAIN,
            FixTarget.STORAGE to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            FixTarget.SERVER_SETUP to Intent.ACTION_MAIN,
        )
        FixTarget.entries.forEach { fix ->
            val intent = SettingsIntents.forFix(fix, app, launch)
            assertEquals(fix.name, expected.getValue(fix), intent.action)
            assertTrue(fix.name, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
    }

    @Test
    fun `runtime permissions and server setup open the app's activity, which asks or shows the step`() {
        val toFront = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        listOf(
            FixTarget.MICROPHONE_PERMISSION,
            FixTarget.NOTIFICATION_PERMISSION,
            FixTarget.LOCAL_NETWORK_PERMISSION,
            FixTarget.SERVER_SETUP,
        ).forEach { fix ->
            val intent = SettingsIntents.forFix(fix, app, launch)
            assertEquals(fix.name, "$app.MainActivity", intent.component?.className)
            assertEquals(fix.name, fix.name, intent.getStringExtra(SettingsIntents.EXTRA_FIX_TARGET))
            assertEquals(fix.name, toFront, intent.flags and toFront)
        }
        assertEquals("the launch intent is copied, not changed", 0, launch.flags)
    }

    @Test
    fun `system screens name the package`() {
        assertEquals("package:$app", SettingsIntents.systemScreen(FixTarget.MICROPHONE_PERMISSION, app).dataString)
        val notifications = SettingsIntents.systemScreen(FixTarget.NOTIFICATION_PERMISSION, app)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
        assertEquals(app, notifications.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        FixTarget.entries.forEach { fix ->
            val screen = SettingsIntents.systemScreen(fix, app)
            assertTrue(fix.name, screen.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(fix.name, null, screen.component)
        }
    }

    @Test
    fun `without a launch intent a permission fix falls back to its system screen`() {
        val microphone = SettingsIntents.forFix(FixTarget.MICROPHONE_PERMISSION, app, launch = null)
        val notifications = SettingsIntents.forFix(FixTarget.NOTIFICATION_PERMISSION, app, launch = null)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, microphone.action)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, notifications.action)
    }

    @Test
    fun `without a launch intent server setup falls back to the app page`() {
        val setup = SettingsIntents.forFix(FixTarget.SERVER_SETUP, app, launch = null)

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, setup.action)
    }
}

@RunWith(AndroidJUnit4::class)
class ClipboardManagerAccessTest {
    private val clipboard =
        ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(
            ClipboardManager::class.java,
        )
    private val access = ClipboardManagerAccess(clipboard, RecordingLogger())

    @Test
    fun `puts a sensitive clip and restores what was there`() {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("user", "earlier"))
        val saved = access.save()

        assertTrue(access.putSensitive("dictated"))
        assertEquals("dictated", clipboard.primaryClip?.getItemAt(0)?.text)
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        access.restore(saved)
        assertEquals("earlier", clipboard.primaryClip?.getItemAt(0)?.text)
    }

    @Test
    fun `an empty or unreadable clipboard is cleared afterwards, so the dictation does not stay`() {
        clipboard.clearPrimaryClip()
        val saved = access.save()

        access.putSensitive("dictated")
        access.restore(saved)

        assertFalse(clipboard.hasPrimaryClip())
        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `the saved clip prints no content`() {
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("user", "earlier"))

        assertEquals("SavedClip(readable=true)", access.save().toString())
    }
}
