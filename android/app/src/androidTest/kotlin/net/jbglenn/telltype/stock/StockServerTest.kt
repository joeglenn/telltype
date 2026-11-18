package net.jbglenn.telltype.stock

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.e2e.Arrange
import net.jbglenn.telltype.e2e.Device
import net.jbglenn.telltype.e2e.Device.HOST
import net.jbglenn.telltype.e2e.Device.WAIT_MS
import net.jbglenn.telltype.e2e.Device.await
import net.jbglenn.telltype.e2e.Device.uiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G1 (D60): the app against stock OpenAI-compatible servers, not our gate. Setup through the real screens with two
 * plain-HTTP addresses and no key (speech on one, the text model on another, as the host's
 * `android/scripts/stock-servers-test.sh` starts them), then a dictation of the sample clip that the text model
 * cleans. Outside the `e2e` package, so `connected-tests.sh` (which needs only our gate) does not run it; run it on a
 * cleared app with `-e owkStockSpeech http://10.0.2.2:<port> -e owkStockText http://10.0.2.2:<port>`.
 */
@RunWith(AndroidJUnit4::class)
class StockServerTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val speechUrl = checkNotNull(arguments.getString(SPEECH_ARGUMENT)) { "pass -e $SPEECH_ARGUMENT <url>" }
    private val textUrl = checkNotNull(arguments.getString(TEXT_ARGUMENT)) { "pass -e $TEXT_ARGUMENT <url>" }

    @Test
    fun setupWithTwoPlainHttpAddressesAndNoKeyThenDictate() {
        Device.openApp()
        await(By.text("Connect to your server"))
        uiDevice.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT_MS).first().text = speechUrl
        Device.awaitScrolling(By.text(textPattern("Advanced: a separate text model address"))).click()
        val fields = uiDevice.wait(Until.findObjects(By.clazz("android.widget.EditText")), WAIT_MS)
        assertEquals("address, key and text model address", 3, fields.size)
        fields[2].text = textUrl
        Device.screenshot("g1-stock-01-two-addresses")
        Device.awaitScrolling(By.text(textPattern("Test connection"))).click()
        val card = await(By.text(Pattern.compile("(?s).*Models chosen.*")), limit = LONG_WAIT_MS, what = "connected")
        assertTrue("no certificate step for HTTP", !uiDevice.hasObject(By.text("Confirm the server's certificate")))
        Log.i(TAG, "setup card: ${card.text.replace('\n', ' ')}")
        Device.screenshot("g1-stock-02-connected")

        val server = Arrange.settings().server
        Log.i(
            TAG,
            "saved: speech ${server.address?.baseUrl}, text ${server.textAddress?.baseUrl}, key ${
                if (server.key == null) "none" else "set"
            }, certificates ${server.certificate}/${server.textCertificate}, models ${server.models}",
        )
        assertEquals(Scheme.HTTP, server.address?.scheme)
        assertEquals(Scheme.HTTP, server.textAddress?.scheme)
        assertNull(server.key)
        assertTrue("a cleanup model was chosen from the list", server.models.cleanup != null)

        dictate()
    }

    private fun dictate() {
        val app = Device.app.packageName
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach { permission ->
            Device.shell("pm grant $app $permission")
        }
        Device.enableService()
        Device.useClipMicrophone()
        val field = Device.openHostField("plain")
        await(By.desc(BUBBLE), what = "the bubble above the keyboard").click()
        val finish = await(By.desc(textPattern("Finish")), what = "the listening bubble")
        Thread.sleep(CLIP_MS)
        finish.click()
        assertTrue(
            "the field contains the dictated sentence",
            uiDevice.wait(Until.hasObject(By.res(HOST, "field").text(Pattern.compile("(?s).*Phoebe.*"))), LONG_WAIT_MS),
        )
        Device.screenshot("g1-stock-03-inserted")
        val entry = Arrange.history().first()
        Log.i(
            TAG,
            "dictation: cleanup ${entry.cleanupStatus?.id}, transcript ${entry.rawTranscript.length} chars, " +
                "typed ${entry.finalText.length} chars, timings ${entry.timings}",
        )
        Log.i(TAG, "sample clip: raw \"${entry.rawTranscript}\" -> typed \"${field.text}\"")
        assertEquals("the text model cleaned it", CleanupStatus.APPLIED, entry.cleanupStatus)
        assertEquals("no recording file is left", emptyList<String>(), Device.awaitNoRecordings())
    }

    private fun textPattern(text: String): Pattern = Pattern.compile(Pattern.quote(text), Pattern.CASE_INSENSITIVE)

    private companion object {
        const val TAG = "StockServerTest"
        const val SPEECH_ARGUMENT = "owkStockSpeech"
        const val TEXT_ARGUMENT = "owkStockText"
        const val BUBBLE = "Dictation bubble"
        const val LONG_WAIT_MS = 120_000L
        const val CLIP_MS = 8_500L
    }
}
