package net.jbglenn.telltype.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review item "lost ✕ tap" (brief A7c section 0): ✕ and ✓ tapped 0.3, 0.6, 1, 1.5, 2 and 3 s after the tap that
 * starts listening must each end Listening. Fake engines, so ✓ does not need the server. Needs a configured app.
 */
@RunWith(AndroidJUnit4::class)
class BubbleTimingTest {
    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
    }

    @After
    fun tearDown() {
        Bubble.fakeEngines(false)
    }

    @Test
    fun cancelAndFinishAreNeverLost() {
        val lost = mutableListOf<String>()
        for (delay in DELAYS_MS) {
            for (button in listOf(CANCEL, FINISH)) {
                tapAfter(delay, button)?.let(lost::add)
            }
        }
        assertEquals("taps that did not end Listening", emptyList<String>(), lost)
    }

    /** One attempt; returns a description of the failure, or null when the tap ended Listening. */
    private fun tapAfter(delay: Long, button: String): String? {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val mic = checkNotNull(Bubble.bounds()) { "no bubble" }
        Device.uiDevice.click(mic.centerX(), mic.centerY())
        val started = SystemClock.uptimeMillis()
        SystemClock.sleep((started + delay - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        val before = Bubble.bounds()
        val target = Bubble.tapControl(button)
        val tapped = SystemClock.uptimeMillis() - started
        val after = Bubble.awaitState(LEAVE_MS) { it?.startsWith("Listening") != true && it != "Starting" }
        Log.i(
            TAG,
            "timing: $button at ${tapped}ms bubble $before target $target -> $after, now ${Bubble.controlBounds(
                button,
            )}",
        )
        val ended = after?.startsWith("Listening") != true && after != "Starting"
        if (!ended) {
            Device.screenshot("lost-$button-$delay")
            Bubble.actionClick(CANCEL)
            Bubble.expectState("Ready")
        } else if (button == FINISH) {
            Bubble.awaitState { it == "Done" || it?.startsWith("Ready") == true }
        }
        return if (ended) null else "$button at $delay ms (tapped at $tapped ms, bubble $before, target $target)"
    }

    private companion object {
        const val CANCEL = "Cancel"
        const val FINISH = "Finish"
        const val LEAVE_MS = 3_000L
        val DELAYS_MS = listOf(300L, 600L, 1_000L, 1_500L, 2_000L, 3_000L)
    }
}
