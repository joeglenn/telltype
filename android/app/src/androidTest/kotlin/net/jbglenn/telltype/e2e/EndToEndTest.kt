package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import net.jbglenn.telltype.e2e.Device.HOST
import net.jbglenn.telltype.e2e.Device.TAG
import net.jbglenn.telltype.e2e.Device.WAIT_MS
import net.jbglenn.telltype.e2e.Device.await
import net.jbglenn.telltype.e2e.Device.uiDevice
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * The whole app on the emulator against the server's real gateway (brief A7b): setup through the real screens, a real
 * dictation into the test host's plain field, and the password and cancel rules. Run in order on a freshly
 * cleared app; the host pushes the gateway key to [KEY_FILE] just before and deletes it afterwards.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EndToEndTest {
    /** A screenshot of the screen a test failed on. */
    @get:Rule
    val failureShots = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            Device.screenshot("fail-${description.methodName}")
        }
    }

    @Test
    fun a_setupThroughTheRealScreens() {
        Device.openApp()
        await(By.text("Connect to your server"))
        val fields = uiDevice.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT_MS)
        assertTrue("address and key fields", fields.size >= 2)
        fields.first().text = GATE_ADDRESS
        val key = Device.shell("cat $KEY_FILE").trim()
        assertTrue("the host must push the key to $KEY_FILE", key.isNotEmpty())
        Device.setTextQuietly(index = 1, text = key)
        Device.awaitScrolling(By.text(textPattern("Test connection"))).click()
        await(By.text("Confirm the server's certificate"))
        // The SHA-256 of the gate's certificate (passed by the host), shown in groups of four bytes.
        val (firstGroup, lastGroup) = Arrange.certificateGroups()
        await(By.text(firstGroup), what = "the server's fingerprint, first group")
        Device.awaitScrolling(By.text(lastGroup))
        Device.screenshot("01-setup-fingerprint")
        Device.awaitScrolling(By.text(textPattern("They match"))).click()
        await(By.text("Connected to your server"), limit = LONG_WAIT_MS)
        Device.screenshot("02-setup-connected")
        next()
        grantInStep("Microphone", PERMISSION_FOREGROUND_ONLY)
        grantInStep("Notifications", PERMISSION_ALLOW)
        await(By.text("Accessibility"))
        Device.enableService()
        Device.openApp()
        await(By.text("Allowed"), what = "the accessibility step showing the service on")
        Device.screenshot("03-setup-accessibility-on")
        next()
        val finish = uiDevice.wait(Until.findObject(By.text(textPattern("Finish"))), WAIT_MS)
        finish?.click()
    }

    @Test
    fun b_dictationEndToEndAgainstServer() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        val field = Device.openHostField("plain")
        val mic = await(By.desc(BUBBLE), what = "the bubble above the keyboard")
        Device.screenshot("04-bubble-ready")
        mic.click()
        val finish = await(By.desc(textPattern("Finish")), what = "the listening bubble")
        Device.screenshot("05-listening")
        Thread.sleep(CLIP_MS)
        finish.click()
        Device.screenshot("06-working")
        assertTrue(
            "the field contains the dictated sentence",
            uiDevice.wait(Until.hasObject(By.res(HOST, "field").text(Pattern.compile("(?s).*Phoebe.*"))), LONG_WAIT_MS),
        )
        Device.screenshot("07-inserted")
        assertTrue("text landed in the plain field", field.text.orEmpty().contains("Phoebe"))
        assertEquals("no recording file is left", emptyList<String>(), Device.awaitNoRecordings())
        showHistoryEntry()
    }

    @Test
    fun c_noBubbleOnAPasswordField() {
        Arrange.configuredApp()
        Device.enableService()
        Device.openHostField("password")
        assertFalse(
            "no bubble on a password field",
            uiDevice.wait(Until.hasObject(By.desc(BUBBLE)), RULE_WAIT_MS),
        )
        Device.screenshot("09-password-no-bubble")
    }

    @Test
    fun d_cancelTypesNothingAndKeepsNoRecording() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        val field = Device.openHostField("plain")
        await(By.desc(BUBBLE)).click()
        await(By.desc(textPattern("Finish")), what = "the listening bubble")
        Thread.sleep(CANCEL_AFTER_MS)
        // Looked up again right before the tap: the pill grows while it starts listening.
        await(By.desc(textPattern("Cancel"))).click()
        assertEquals("cancel deleted the recording", emptyList<String>(), Device.awaitNoRecordings())
        assertTrue("the bubble left Listening", uiDevice.wait(Until.gone(By.desc(textPattern("Finish"))), WAIT_MS))
        await(By.desc(BUBBLE), what = "the bubble back to ready")
        Thread.sleep(RULE_WAIT_MS)
        assertTrue("cancel typed nothing", field.text.isNullOrEmpty() || !field.text.contains("Phoebe"))
        assertEquals("cancel left no recording", emptyList<String>(), Device.awaitNoRecordings())
        Device.screenshot("10-cancelled")
    }

    @Test
    fun e_disconnectingTheServiceRemovesTheBubble() {
        Arrange.configuredApp()
        Device.enableService()
        Device.openHostField("plain")
        await(By.desc(BUBBLE), what = "the bubble while connected")
        Device.shell("settings delete secure enabled_accessibility_services")
        assertTrue("the bubble is removed on disconnect", uiDevice.wait(Until.gone(By.desc(BUBBLE)), WAIT_MS))
        assertFalse("the bridge reports disconnected", AccessibilityServiceBridge.shared.connected.value)
        Device.screenshot("11-disconnected-no-bubble")
    }

    private fun showHistoryEntry() {
        Device.openApp()
        await(By.text("History")).click()
        val entry = await(By.text(Pattern.compile("(?s).*Phoebe.*")), what = "the History entry")
        entry.click()
        uiDevice.waitForIdle()
        Thread.sleep(RULE_WAIT_MS)
        Device.screenshot("08-history-entry")
        // The details (app, category, statuses, per-stage timings) are below the texts; collect them page by page.
        val details = linkedSetOf<String>()
        repeat(DETAIL_PAGES) { page ->
            uiDevice.findObjects(By.textContains("")).mapNotNull(UiObject2::getText)
                .filterNot { it.contains("Phoebe") || it.contains("portrait") }
                .forEach(details::add)
            uiDevice.findObject(By.scrollable(true))?.scroll(Direction.DOWN, SCROLL_PAGE)
            if (page == DETAIL_PAGES - 1) Device.screenshot("08b-history-timings")
        }
        val timings = details.filter { DURATION.matcher(it).find() }
        Log.i(TAG, "history details: ${details.joinToString(" | ")}")
        assertTrue("History shows per-stage timings", timings.isNotEmpty())
    }

    private fun next() {
        Device.awaitScrolling(By.text(textPattern("Next"))).click()
    }

    private fun grantInStep(title: String, button: String) {
        await(By.text(title))
        Device.awaitScrolling(By.text(textPattern("Allow"))).click()
        uiDevice.wait(Until.findObject(By.res(PERMISSION_CONTROLLER, button)), WAIT_MS)?.click()
        await(By.text("Allowed"), what = "$title granted")
        next()
    }

    private fun textPattern(text: String): Pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE)

    private companion object {
        const val BUBBLE = "Dictation bubble"
        const val GATE_ADDRESS = "10.0.2.2:8170"
        const val KEY_FILE = "/data/local/tmp/owk-gate.key"
        const val PERMISSION_CONTROLLER = "com.android.permissioncontroller"
        const val PERMISSION_FOREGROUND_ONLY = "permission_allow_foreground_only_button"
        const val PERMISSION_ALLOW = "permission_allow_button"
        const val LONG_WAIT_MS = 90_000L
        const val RULE_WAIT_MS = 3_000L
        const val CLIP_MS = 8_500L
        const val CANCEL_AFTER_MS = 4_000L
        const val DETAIL_PAGES = 4
        const val SCROLL_PAGE = 0.8f
        val DURATION: Pattern = Pattern.compile("\\d+(\\.\\d+)?\\s?(ms|s)\\b")
    }
}
