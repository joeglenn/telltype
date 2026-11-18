package net.jbglenn.telltype.e2e

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import net.jbglenn.telltype.e2e.Device.HOST
import net.jbglenn.telltype.e2e.Device.WAIT_MS
import net.jbglenn.telltype.e2e.Device.uiDevice
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Steps the error-table and gesture tests share: a dictation, the field's text, the failure screenshot. */
internal object Scenario {
    /** Listening time for a dictation with the clip microphone (speech starts at once). */
    const val SPEAK_MS = 2_000L
    const val LONG_WAIT_MS = 60_000L
    const val SHORT_WAIT_MS = 3_000L
    const val PHOEBE = "(?s).*Phoebe.*"

    /** Takes a screenshot named after the failed test. */
    val failureShots = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            Device.screenshot("fail-${description.className.substringAfterLast('.')}-${description.methodName}")
        }
    }

    /** Opens a fresh plain field and waits for the Ready bubble. */
    fun field(type: String = "plain"): UiObject2 {
        val field = Device.openHostField(type)
        // A Problem stays until it is dismissed (it keeps its audio), also in a new field.
        if (Bubble.awaitState { it != null }?.startsWith("Problem") == true) Bubble.actionClick("Dismiss")
        Bubble.expectState("Ready")
        return field
    }

    /** Starts listening, speaks for [speakMs], and taps ✓ (through its accessibility action). */
    fun dictate(speakMs: Long = SPEAK_MS) {
        Bubble.startListening()
        SystemClock.sleep(speakMs)
        Bubble.actionClick("Finish")
    }

    /** The text of the host's field right now (an empty field reads null on API 35). */
    fun fieldText(): String = uiDevice.findObject(By.res(HOST, "field"))?.text.orEmpty()

    /** Waits until the host's field matches [regex]. */
    fun awaitFieldText(regex: String, limit: Long = LONG_WAIT_MS): Boolean =
        uiDevice.wait(Until.hasObject(By.res(HOST, "field").text(Pattern.compile(regex))), limit)

    /** Asserts that the field never gets the dictated text within [limit]. */
    fun assertNotTyped(limit: Long = SHORT_WAIT_MS) {
        SystemClock.sleep(limit)
        assertTrue("nothing typed, field has ${fieldText().length} chars", fieldText().isEmpty())
    }

    /** Waits up to [limit] for [condition]. */
    fun eventually(limit: Long = WAIT_MS, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + limit
        while (!condition()) {
            if (SystemClock.uptimeMillis() > deadline) return false
            SystemClock.sleep(POLL_MS)
        }
        return true
    }

    private const val POLL_MS = 200L
}
