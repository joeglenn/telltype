package net.jbglenn.telltype.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlin.math.abs
import net.jbglenn.telltype.e2e.Device.TAG
import net.jbglenn.telltype.e2e.Device.uiDevice
import net.jbglenn.telltype.e2e.Scenario.PHOEBE
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Raw and Undo after a dictation, hold-to-talk, drag with edge snap and snooze, rotation while listening. */
@RunWith(AndroidJUnit4::class)
class GesturesTest {
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
        Device.shell("settings put system user_rotation 0")
    }

    @Test
    fun rawReplacesTheCleanedTextAndUndoRemovesIt() {
        Bubble.faults("cleanup=rewrite")
        Scenario.field()
        Scenario.dictate()
        assertTrue("cleaned text typed", Scenario.awaitFieldText("I don't wish(?s).*"))
        val seen = linkedSetOf<String>()
        Scenario.eventually(CHIPS_MS) {
            seen +=
                "${Bubble.state()} raw=${Bubble.controlBounds(
                    "Raw",
                ) != null} undo=${Bubble.controlBounds("Undo") != null}"
            Bubble.controlBounds("Raw") != null
        }
        Log.i(TAG, "raw/undo: after typing $seen")
        Bubble.control("Raw")
        Device.screenshot("g-done-chips")
        Bubble.actionClick("Raw")
        assertTrue(
            "Raw typed the transcript without cleanup",
            Scenario.awaitFieldText("Well, I don't(?s).*", Device.WAIT_MS),
        )
        Bubble.control("Undo")
        Bubble.actionClick("Undo")
        assertTrue("Undo removed the text", Scenario.eventually { !Scenario.fieldText().contains("wish") })
        Log.i(TAG, "raw/undo: field now ${Scenario.fieldText().length} chars")
    }

    @Test
    fun holdToTalkRecordsWhileHeldAndFinishesOnRelease() {
        Scenario.field()
        val mic = checkNotNull(Bubble.bounds()) { "no bubble" }
        // A swipe that does not move: 400 steps of about 5 ms hold the finger down for about 2 s.
        uiDevice.swipe(mic.centerX(), mic.centerY(), mic.centerX(), mic.centerY(), HOLD_STEPS)
        val after = Bubble.awaitState { it?.startsWith("Listening") != true && it != "Starting" }
        Log.i(TAG, "hold: after release '$after'")
        assertTrue("released hold finished the dictation", Scenario.awaitFieldText(PHOEBE))
    }

    /**
     * The bubble follows the finger in screen coordinates (no overshoot, slow or fast), snaps to the nearer edge on
     * release (left, then right) and leaves the bubble Ready.
     */
    @Test
    fun dragSnapsToTheNearerEdgeAndTheSnoozeBandHidesTheBubble() {
        Scenario.field()
        val width = Device.app.resources.displayMetrics.widthPixels
        val start = settledBounds()
        // Slow (1.5 s) to the left quarter and 300 px up, then fast (40 ms) to the right three quarters.
        val left = drag(start, width / 4, start.centerY() - DRAG_UP, SLOW_DRAG_STEPS, "slow")
        Device.screenshot("g-dragged-left")
        assertTrue("snapped to the left edge: $left", left.left < width / 8)
        assertTrue(
            "dragging does not start a dictation: ${Bubble.state()}",
            Bubble.state()?.startsWith("Ready") == true,
        )
        val right = drag(left, width * 3 / 4, left.centerY() + DRAG_UP / 2, FAST_DRAG_STEPS, "fast")
        Device.screenshot("g-dragged-right")
        assertTrue("snapped to the right edge: $right", right.right > width - width / 8)
        assertTrue("still Ready: ${Bubble.state()}", Bubble.state()?.startsWith("Ready") == true)
    }

    /** Each snooze target under the finger has exactly its own effect; each is undone, so the bubble is back. */
    @Test
    fun aDropOnEachSnoozeTargetHasExactlyThatEffect() {
        val width = Device.app.resources.displayMetrics.widthPixels
        try {
            dropOnBand(width / 6)
            assertTrue("hidden for an hour", Arrange.snoozedUntil() != null)
            assertFalse("not excluded by 'an hour'", Arrange.isExcluded(Device.HOST))
            Arrange.resumeSnooze()

            dropOnBand(width / 2)
            assertTrue("'this app' sets no hour snooze", Arrange.snoozedUntil() == null)
            assertFalse("'this app' adds no rule", Arrange.isExcluded(Device.HOST))
            Device.openHostField("plain")
            assertFalse("still hidden in this app", uiDevice.wait(Until.hasObject(By.desc(Bubble.NAME)), RULE_MS))
            reconnect()

            dropOnBand(width - width / 6)
            assertTrue("never in this app: excluded", Arrange.isExcluded(Device.HOST))
            assertTrue("'never' sets no hour snooze", Arrange.snoozedUntil() == null)
        } finally {
            Arrange.resumeSnooze()
            Arrange.excluded(Device.HOST, false)
        }
        assertTrue("the bubble is back after undoing every target", Scenario.field().let { Bubble.bounds() != null })
    }

    /**
     * Presses the bubble at [from]'s centre, drags to ([toX], [toY]) in [steps] moves, measures the bubble with the
     * finger still down (it must be centred under the finger), releases and returns where it settled.
     */
    private fun drag(from: Rect, toX: Int, toY: Int, steps: Int, label: String): Rect {
        val finger = Finger.down(from.centerX(), from.centerY())
        finger.slideTo(toX, toY, steps)
        SystemClock.sleep(SETTLE_HELD_MS)
        val held = checkNotNull(Bubble.bounds()) { "no bubble while dragging" }
        finger.up()
        SystemClock.sleep(SETTLE_MS)
        val settled = checkNotNull(Bubble.bounds()) { "no bubble after the drop" }
        val dx = held.centerX() - toX
        val dy = held.centerY() - toY
        Log.i(
            TAG,
            "drag $label: finger ${from.centerX()},${from.centerY()} -> $toX,$toY " +
                "(requested ${toX - from.centerX()},${toY - from.centerY()} px in $steps moves); " +
                "bubble $held (before ${from.width()}x${from.height()}) " +
                "centred at ${held.centerX()},${held.centerY()} (off by $dx,$dy); settled $settled",
        )
        assertTrue(
            "$label drag: bubble centre $dx,$dy px from the finger",
            abs(dx) <= FINGER_TOLERANCE && abs(dy) <= FINGER_TOLERANCE,
        )
        assertTrue(
            "$label drag: dropped at the height of the finger (${settled.centerY()} for $toY)",
            abs(settled.centerY() - toY) <= FINGER_TOLERANCE,
        )
        return settled
    }

    /** The bubble's bounds once two reads [SETTLE_HELD_MS] apart agree (after a previous test it may still move). */
    private fun settledBounds(): Rect {
        var before = checkNotNull(Bubble.bounds()) { "no bubble" }
        repeat(SETTLE_TRIES) {
            SystemClock.sleep(SETTLE_HELD_MS)
            val now = checkNotNull(Bubble.bounds()) { "no bubble" }
            if (now == before) return now
            before = now
        }
        return before
    }

    /** Drags the bubble onto the snooze band at column [x] and checks that it went away. */
    private fun dropOnBand(x: Int) {
        val start = checkNotNull(Scenario.field().let { Bubble.bounds() }) { "no bubble before the drop" }
        val height = Device.app.resources.displayMetrics.heightPixels
        val finger = Finger.down(start.centerX(), start.centerY())
        finger.slideTo(x, height - height / BAND_FRACTION, DRAG_STEPS)
        SystemClock.sleep(SETTLE_HELD_MS)
        Device.screenshot("g-band-$x")
        finger.up()
        assertTrue("the drop at x=$x hid the bubble", uiDevice.wait(Until.gone(By.desc(Bubble.NAME)), Device.WAIT_MS))
        Log.i(TAG, "snooze drop at x=$x: until ${Arrange.snoozedUntil()}, excluded ${Arrange.isExcluded(Device.HOST)}")
    }

    /** A new connection of the service, which forgets the "this app" snooze. */
    private fun reconnect() {
        Device.shell("settings delete secure enabled_accessibility_services")
        Scenario.eventually {
            !net.jbglenn.telltype.platform.service.AccessibilityServiceBridge.shared.connected.value
        }
        Device.enableService()
    }

    @Test
    fun rotatingWhileListeningKeepsTheDictation() {
        Device.shell("settings put system accelerometer_rotation 0")
        Scenario.field()
        Bubble.startListening()
        Device.shell("settings put system user_rotation 1")
        SystemClock.sleep(ROTATE_MS)
        Device.screenshot("g-rotated-listening")
        assertTrue("still listening after rotation: ${Bubble.state()}", Bubble.state()?.startsWith("Listening") == true)
        Bubble.actionClick("Finish")
        // The host's activity is recreated by the rotation, so its field is a new one: typed, or handed off.
        val kept = Scenario.eventually(Scenario.LONG_WAIT_MS) {
            Scenario.fieldText().contains("Phoebe") || Arrange.latestHistory()?.finalText?.contains("Phoebe") == true
        }
        Device.screenshot("g-rotated-finished")
        Log.i(
            TAG,
            "rotation: field has text ${Scenario.fieldText().contains(
                "Phoebe",
            )}, via ${Arrange.latestHistory()?.insertedVia}",
        )
        assertTrue("the dictation survived the rotation", kept)
        Device.shell("settings put system user_rotation 0")
        SystemClock.sleep(ROTATE_MS)
    }

    private companion object {
        const val HOLD_STEPS = 400
        const val CHIPS_MS = 6_000L
        const val DRAG_STEPS = 60
        const val FAST_DRAG_STEPS = 8
        const val FINGER_TOLERANCE = 4
        const val SETTLE_HELD_MS = 400L
        const val SETTLE_TRIES = 10

        /** About 1.5 s: a deliberate drag. */
        const val SLOW_DRAG_STEPS = 300
        const val DRAG_UP = 300
        const val SETTLE_MS = 1_000L
        const val BAND_FRACTION = 20
        const val RULE_MS = 3_000L
        const val ROTATE_MS = 2_500L
    }
}
