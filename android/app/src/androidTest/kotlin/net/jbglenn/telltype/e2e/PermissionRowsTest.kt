package net.jbglenn.telltype.e2e

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import net.jbglenn.telltype.e2e.Device.TAG
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two permission rows of the error table. Revoking a runtime permission kills the app's process, and these
 * tests run in it, so [microphonePermissionMissing] needs the host to revoke first and runs on its own:
 * `adb shell pm revoke <app id> android.permission.RECORD_AUDIO`, then `am instrument -e class ...#microphone...`
 * (android/docs/integration.md). It grants the permission back at the end.
 */
@RunWith(AndroidJUnit4::class)
class PermissionRowsTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Test
    fun accessibilityOffPostsANoticeAndHomeShowsTheCard() {
        Arrange.configuredApp()
        Device.enableService()
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        Device.shell("settings delete secure enabled_accessibility_services")
        assertTrue("disconnected", Scenario.eventually { !AccessibilityServiceBridge.shared.connected.value })
        val posted = Scenario.eventually {
            Device.shell("dumpsys notification --noredact").contains("Bubble is off")
        }
        Device.shell("cmd statusbar expand-notifications")
        Device.screenshot("et-accessibility-off-notice")
        Device.shell("cmd statusbar collapse")
        Device.openApp()
        Device.awaitScrolling(By.textContains("Accessibility is off"))
        Device.screenshot("et-accessibility-off-home")
        Device.enableService()
        assertTrue("the notice 'Bubble is off' is posted", posted)
    }

    @Test
    fun aProblemWhileTheBubbleIsHiddenPostsANotice() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
        Bubble.faults("stt=empty")
        try {
            Device.shell("cmd notification cancel_all ${Device.app.packageName}")
            Device.openHostField("plain")
            Bubble.startListening()
            // Leave the field while listening (a running session keeps its bubble), then finish: the problem that
            // follows has no field, so no bubble to show on.
            Device.uiDevice.pressHome()
            Bubble.control("Finish")
            Bubble.actionClick("Finish")
            val posted = Scenario.eventually(Scenario.LONG_WAIT_MS) {
                Device.shell("dumpsys notification --noredact").contains("Dictation problem")
            }
            Device.shell("cmd statusbar expand-notifications")
            Device.screenshot("et-problem-while-hidden-notice")
            Device.shell("cmd statusbar collapse")
            assertTrue("the notice 'Dictation problem' is posted", posted)
        } finally {
            if (Bubble.state()?.startsWith("Listening") == true) Bubble.actionClick("Cancel")
            Bubble.faults()
            Bubble.fakeEngines(false)
        }
    }

    @Test
    fun microphonePermissionMissing() {
        val granted = Device.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        check(granted == PackageManager.PERMISSION_DENIED) { "revoke RECORD_AUDIO from the host before this test" }
        try {
            Device.enableService()
            Device.openHostField("plain")
            val state = Bubble.expectState("Unavailable")
            Bubble.actionClick(Bubble.NAME)
            val chip = Bubble.control("Fix")
            Device.screenshot("et-mic-permission-missing")
            Log.i(TAG, "mic permission missing: '$state', Fix chip at $chip")
            assertEquals("Unavailable. Microphone needed", state)
            assertEquals("nothing recorded", emptyList<String>(), Device.recordings())
            val onScreen = chip.left >= 0 && chip.right <= Device.uiDevice.displayWidth
            assertTrue("the Fix chip $chip is on the screen", onScreen)
            Bubble.tapControl("Fix")
            val dialog = Device.uiDevice.wait(Until.hasObject(By.pkg(PERMISSION_DIALOG)), Device.WAIT_MS)
            Device.screenshot("et-mic-permission-fix-dialog")
            Log.i(TAG, "mic permission missing: Fix opened the permission dialog: $dialog")
            assertTrue("Fix opens the microphone permission dialog", dialog)
            Device.uiDevice.wait(
                Until.findObject(By.res(PERMISSION_DIALOG_PACKAGE, ALLOW_WHILE_IN_USE)),
                Device.WAIT_MS,
            )
                ?.click()
            assertTrue(
                "allowed in the dialog",
                Scenario.eventually { Device.app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == GRANTED },
            )
            Device.openHostField("plain")
            Log.i(TAG, "mic permission allowed: bubble '${Bubble.expectState("Ready")}'")
            Device.screenshot("et-mic-permission-allowed-ready")
        } finally {
            Device.shell("pm grant ${Device.app.packageName} ${Manifest.permission.RECORD_AUDIO}")
        }
    }

    private companion object {
        /** The system's permission controller (AOSP or Google build). */
        val PERMISSION_DIALOG: Pattern = Pattern.compile(".*permissioncontroller")
        const val PERMISSION_DIALOG_PACKAGE = "com.android.permissioncontroller"
        const val ALLOW_WHILE_IN_USE = "permission_allow_foreground_only_button"
        const val GRANTED = PackageManager.PERMISSION_GRANTED
    }
}
