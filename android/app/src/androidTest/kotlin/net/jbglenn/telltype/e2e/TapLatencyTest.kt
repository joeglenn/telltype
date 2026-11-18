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
 * Tap-to-listening latency (from finger down to the Listening state), for presses held 0 ms and 100 ms: the session
 * starts when a tap lifts (A8c), so that a press that becomes a drag never starts the microphone service. Logs the
 * samples and medians (`tap latency:` in logcat); every tap must reach Listening.
 */
@RunWith(AndroidJUnit4::class)
class TapLatencyTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
        Bubble.faults()
    }

    @After
    fun tearDown() {
        Bubble.fakeEngines(false)
    }

    @Test
    fun tapToListeningLatency() {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        for (pressMs in listOf(0L, 100L)) {
            val samples = List(TAPS) { tapToListening(pressMs) }
            Log.i(TAG, "tap latency: press $pressMs ms, median ${samples.sorted()[TAPS / 2]} ms, samples $samples")
            assertTrue("every tap reached Listening: $samples", samples.all { it < LIMIT_MS })
        }
    }

    /**
     * Hold-to-talk (decision D57): milliseconds from finger down to Listening while the finger stays down. A still
     * finger starts the session at the start threshold (150 ms since A9b; A8c started it at the 350 ms hold
     * threshold). Each hold is then slid away and released, which cancels it.
     */
    @Test
    fun holdToListeningLatency() {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val samples = List(TAPS) { holdToListening() }
        Log.i(TAG, "hold latency: median ${samples.sorted()[TAPS / 2]} ms, samples $samples")
        assertTrue("every hold reached Listening: $samples", samples.all { it < LIMIT_MS })
    }

    private fun holdToListening(): Long {
        val at = checkNotNull(BubbleVisibility.settledBounds()) { "no bubble" }
        val finger = Finger.down(at.centerX(), at.centerY())
        val downAt = SystemClock.uptimeMillis()
        while (Bubble.state()?.startsWith("Listening") != true && SystemClock.uptimeMillis() - downAt < LIMIT_MS) {
            SystemClock.sleep(POLL_MS)
        }
        val latency = SystemClock.uptimeMillis() - downAt
        finger.slideTo(at.centerX(), (at.centerY() - SLIDE_AWAY_PX).coerceAtLeast(0), SLIDE_STEPS)
        finger.up()
        Bubble.expectState("Ready")
        SystemClock.sleep(SETTLE_MS)
        return latency
    }

    /** Milliseconds from finger down to Listening for one tap held [pressMs]; then cancels back to Ready. */
    private fun tapToListening(pressMs: Long): Long {
        val at = checkNotNull(BubbleVisibility.settledBounds()) { "no bubble" }
        val finger = Finger.down(at.centerX(), at.centerY())
        val downAt = SystemClock.uptimeMillis()
        if (pressMs > 0) SystemClock.sleep(pressMs)
        finger.up()
        while (Bubble.state()?.startsWith("Listening") != true && SystemClock.uptimeMillis() - downAt < LIMIT_MS) {
            SystemClock.sleep(POLL_MS)
        }
        val latency = SystemClock.uptimeMillis() - downAt
        Bubble.awaitState { it?.startsWith("Listening") == true }
        Bubble.control(CANCEL)
        Bubble.actionClick(CANCEL)
        Bubble.expectState("Ready")
        SystemClock.sleep(SETTLE_MS)
        return latency
    }

    private companion object {
        const val TAPS = 9
        const val LIMIT_MS = 5_000L
        const val POLL_MS = 5L
        const val SETTLE_MS = 1_000L
        const val CANCEL = "Cancel"
        const val SLIDE_AWAY_PX = 700
        const val SLIDE_STEPS = 10
    }
}
