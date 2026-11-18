package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipFile
import net.jbglenn.telltype.e2e.Device.TAG
import net.jbglenn.telltype.e2e.Device.WAIT_MS
import net.jbglenn.telltype.e2e.Device.await
import net.jbglenn.telltype.e2e.Device.uiDevice
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
 * Diagnostics on the emulator against the server (brief A7c section 4). Runs on a cleared app (`pm clear`, as
 * `connected-tests.sh` does) or one installed by `android/scripts/deploy.sh`: [a_configureServer] grants the two
 * runtime permissions the way deploy.sh does (`pm clear` revokes them, and Setup rightly keeps Next disabled until
 * the microphone is allowed), switches the service on and connects to the server through the setup screens,
 * [b_selfTestPassesAgainstServer] runs the self-test with the bundled sample and
 * [c_debugBundleHoldsNoKeyAndNoDictatedText] exports the debug bundle and reads it back. Run one method at a time
 * with `am instrument -e class ...DiagnosticsTest#<method>`; the host pushes the key to [KEY_FILE] for a and c.
 * `am instrument` stops the app, which switches its accessibility service off: run `deploy.sh --no-build` afterwards.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DiagnosticsTest {
    /** A screenshot of the screen a test failed on. */
    @get:Rule
    val failureShots = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            Device.screenshot("fail-${description.methodName}")
        }
    }

    @Test
    fun a_configureServer() {
        // `am instrument` stops the app first, which unbinds its accessibility service while it stays listed in the
        // setting; writing the same value again changes nothing, so take it off and put it back.
        Device.shell("settings delete secure enabled_accessibility_services")
        Device.enableService()
        RUNTIME_PERMISSIONS.forEach { Device.shell("pm grant ${Device.app.packageName} $it") }
        Device.openApp()
        await(By.text("Connect to your server"))
        val fields = uiDevice.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT_MS)
        assertTrue("address and key fields", fields.size >= 2)
        fields.first().text = GATE_ADDRESS
        Device.setTextQuietly(index = 1, text = gateKey())
        Device.awaitScrolling(By.text(caseless("Test connection"))).click()
        await(By.text(Arrange.certificateGroups().first), what = "the server's fingerprint")
        Device.awaitScrolling(By.text(caseless("They match"))).click()
        await(By.text("Connected to your server"), limit = LONG_WAIT_MS)
        // The permission and accessibility steps show "Allowed" now: walk through to Finish.
        val finished = (1..SETUP_STEPS).any {
            val finish = uiDevice.wait(Until.findObject(By.text(caseless("Finish"))), STEP_WAIT_MS)
            finish?.click() ?: uiDevice.wait(Until.findObject(By.text(caseless("Next"))), STEP_WAIT_MS)?.click()
            finish != null
        }
        assertTrue("setup reached Finish", finished)
        await(By.text("History"), what = "the app's main screens after setup")
        Device.screenshot("diag-01-setup-done")
    }

    @Test
    fun b_selfTestPassesAgainstServer() {
        openDiagnostics()
        Device.awaitScrolling(By.text(caseless("Run"))).click()
        val result = await(By.text(SELF_TEST_RESULT), limit = LONG_WAIT_MS, what = "the self-test result")
        val texts = screenTexts()
        Log.i(
            TAG,
            "self-test: ${texts.filter {
                DURATION.matcher(it).find() || it == result.text
            }.joinToString(" | ")}",
        )
        Device.screenshot("diag-02-self-test")
        assertTrue("the self-test passed: ${result.text}", result.text.startsWith("Passed"))
    }

    @Test
    fun c_debugBundleHoldsNoKeyAndNoDictatedText() {
        openDiagnostics()
        val bundle = File(Device.app.cacheDir, "exports/telltype-debug.zip")
        bundle.delete()
        Device.awaitScrolling(By.text(caseless("Export"))).click()
        assertTrue("the share sheet opened", uiDevice.wait(Until.gone(By.text(caseless("Export"))), WAIT_MS))
        Device.screenshot("diag-03-share-sheet")
        uiDevice.pressBack()
        assertTrue("the bundle was written", bundle.isFile)
        val key = gateKey()
        ZipFile(bundle).use { zip ->
            zip.entries().toList().forEach { entry ->
                val content = zip.getInputStream(entry).use { it.readBytes().decodeToString() }
                Log.i(TAG, "bundle entry ${entry.name} ${entry.size} bytes")
                assertFalse("${entry.name} holds the key", content.contains(key))
                DICTATED.forEach { word ->
                    assertFalse("${entry.name} holds dictated text", content.contains(word, ignoreCase = true))
                }
            }
        }
    }

    private fun openDiagnostics() {
        Device.openApp()
        await(By.text("Settings")).click()
        Device.awaitScrolling(By.text("Diagnostics")).click()
        await(By.text("Permissions and service"), what = "the Diagnostics screen")
    }

    private fun gateKey(): String = Device.shell("cat $KEY_FILE").trim().also {
        assertTrue("the host must push the key to $KEY_FILE", it.isNotEmpty())
    }

    private fun screenTexts(): List<String> = uiDevice.findObjects(By.textContains("")).mapNotNull(UiObject2::getText)

    private fun caseless(text: String): Pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE)

    private companion object {
        const val GATE_ADDRESS = "10.0.2.2:8170"
        const val KEY_FILE = "/data/local/tmp/owk-gate.key"
        const val LONG_WAIT_MS = 90_000L
        const val STEP_WAIT_MS = 3_000L
        const val SETUP_STEPS = 6

        /** What deploy.sh grants; `pm clear` revokes both. */
        val RUNTIME_PERMISSIONS = listOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS")

        /** Words of the clip the measurement runs dictate; none may reach the bundle. */
        val DICTATED = listOf("Phoebe", "portrait", "turning away her eyes")
        val SELF_TEST_RESULT: Pattern = Pattern.compile("^(Passed.*|Failed.*|Finished, but.*)$")
        val DURATION: Pattern = Pattern.compile("\\d+(\\.\\d+)?\\s?(ms|s)\\b")
    }
}
