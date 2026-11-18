package net.jbglenn.telltype.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bubble docked on the left edge (A7h; the earlier rounds checked the right edge on the emulator and the left
 * only in JVM tests): dragged there, it still takes ✕ and ✓ 300 ms after the tap that starts listening, and its
 * Listening pill lies whole on the screen with both buttons. The bubble is docked right again afterwards. Fake
 * engines, so ✓ does not need the server. Needs a configured app.
 */
@RunWith(AndroidJUnit4::class)
class LeftEdgeTest {
    @get:Rule
    val shots = Scenario.failureShots

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
    fun aBubbleOnTheLeftEdgeTakesCancelAndFinishAndShowsTheWholePill() {
        val width = Device.uiDevice.displayWidth
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        dock(width / 4)
        try {
            listOf(CANCEL, FINISH).forEach { tapAt300Ms(it, width) }
            Bubble.startListening()
            SystemClock.sleep(PILL_MS)
            val pill = checkNotNull(Bubble.bounds()) { "no pill" }
            val cancel = Bubble.control(CANCEL)
            val finish = Bubble.control(FINISH)
            Device.screenshot("left-edge-listening")
            Log.i(TAG, "left edge: pill $pill, cancel $cancel, finish $finish, screen width $width")
            assertTrue("the pill $pill is on the screen", pill.left >= 0 && pill.right <= width)
            assertTrue("both buttons inside the pill", cancel.left >= pill.left && finish.right <= pill.right)
            Bubble.actionClick(CANCEL)
            Bubble.expectState("Ready")
        } finally {
            dock(width * 3 / 4)
        }
    }

    /** Starts listening from Ready on the left edge and taps [button] 300 ms later; it must end Listening. */
    private fun tapAt300Ms(button: String, width: Int) {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val mic = checkNotNull(Bubble.bounds()) { "no bubble" }
        assertTrue("docked on the left edge: $mic", mic.left < width / LEFT_FRACTION)
        Device.uiDevice.click(mic.centerX(), mic.centerY())
        val started = SystemClock.uptimeMillis()
        SystemClock.sleep(TAP_AT_MS)
        val target = Bubble.tapControl(button)
        val tapped = SystemClock.uptimeMillis() - started
        val after = Bubble.awaitState(LEAVE_MS) { it?.startsWith("Listening") != true && it != "Starting" }
        Log.i(TAG, "left edge: $button at ${tapped}ms on $target (bubble was $mic) -> '$after'")
        assertTrue("$button at $target is on the screen", target.left >= 0 && target.right <= width)
        assertTrue("$button ended Listening: '$after'", after?.startsWith("Listening") != true && after != "Starting")
        if (button == FINISH) Bubble.awaitState { it == "Done" || it?.startsWith("Ready") == true }
    }

    /** Drags the bubble horizontally to column [toX]; it snaps to the nearer edge. */
    private fun dock(toX: Int) {
        val start = checkNotNull(Bubble.bounds()) { "no bubble to drag" }
        val finger = Finger.down(start.centerX(), start.centerY())
        finger.slideTo(toX, start.centerY(), DRAG_STEPS)
        finger.up()
        SystemClock.sleep(SETTLE_MS)
        Log.i(TAG, "left edge: dragged from $start to x=$toX, now ${Bubble.bounds()}")
    }

    private companion object {
        const val CANCEL = "Cancel"
        const val FINISH = "Finish"
        const val TAP_AT_MS = 300L
        const val LEAVE_MS = 3_000L
        const val PILL_MS = 2_000L
        const val SETTLE_MS = 1_000L
        const val DRAG_STEPS = 30
        const val LEFT_FRACTION = 8
    }
}
