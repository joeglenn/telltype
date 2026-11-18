package net.jbglenn.telltype.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import kotlin.random.Random
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Board B1: on the Pixel a quick press-and-drag to the right made the bubble vanish for good, while the app kept
 * opening its window. Quick presses that turn into drags, with the real engines, from both edges, ending at or past
 * the right edge, in a plain field and in a chat row at the bottom (there also with the keyboard hidden and shown
 * again after each drop); none may leave a recording (a drag starts no session). After each drop the bubble must
 * be on the screen by its node, by its window frame and by its pixels. And switching the bubble off and on brings
 * it back at its default place.
 */
@RunWith(AndroidJUnit4::class)
class BubbleStaysVisibleTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(false)
        Bubble.faults()
        BubbleVisibility.forgetDraggedPlace()
    }

    @Test
    fun quickPressAndDragsNeverLoseTheBubble() {
        val width = Device.uiDevice.displayWidth
        val height = Device.uiDevice.displayHeight
        val random = Random(SEED)
        val failures = mutableListOf<String>()
        val recordingsBefore = Device.recordings().toSet()
        repeat(ROUNDS) { round ->
            if (round % ROUNDS_PER_SCREEN == 0) {
                when (round / ROUNDS_PER_SCREEN % SCREENS) {
                    1 -> Device.openHostField("plain")
                    else -> Device.openChatField()
                }
                Bubble.expectState("Ready")
            }
            val from = checkNotNull(BubbleVisibility.settledBounds()) { "round $round: no bubble before the press" }
            val toX = listOf(width - 2, width + 40, width * 3 / 4, 30, width / 2)[random.nextInt(5)]
            val toY = (height * (0.15 + 0.5 * random.nextDouble())).toInt()
            val wait = listOf(0L, 30L, 80L, 150L, 300L)[random.nextInt(5)]
            val steps = listOf(2, 4, 8, 20)[random.nextInt(4)]
            val finger = Finger.down(from.centerX(), from.centerY())
            SystemClock.sleep(wait)
            finger.slideTo(toX.coerceAtMost(width - 1), toY, steps)
            finger.up()
            Bubble.awaitState { it?.startsWith("Ready") == true }
            SystemClock.sleep(SETTLE_MS)
            val problem = check(width, height)
            Log.i(
                TAG,
                "stays visible: round $round from $from wait $wait steps $steps to $toX,$toY -> ${problem ?: "ok"}",
            )
            if (problem != null) {
                Device.screenshot("stays-visible-$round")
                failures += "round $round: $problem"
            }
            if (round / ROUNDS_PER_SCREEN % SCREENS == KEYBOARD_CYCLE_SCREEN) {
                keyboardDownAndUp(width, height)?.let { failures += "round $round, keyboard down and up: $it" }
            }
        }
        assertTrue("the bubble was lost: $failures", failures.isEmpty())
        // A press that turns into a drag starts no session (A8c), so no drag leaves a recording behind.
        assertEquals("no recording from a drag", recordingsBefore, Device.recordings().toSet())
    }

    @Test
    fun switchingTheBubbleOffAndOnBringsItBackAtItsDefaultPlace() {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val home = checkNotNull(BubbleVisibility.settledBounds()) { "no bubble" }
        val finger = Finger.down(home.centerX(), home.centerY())
        finger.slideTo(Device.uiDevice.displayWidth / 5, home.centerY() / 2, DRAG_STEPS)
        finger.up()
        SystemClock.sleep(SETTLE_MS)
        val dragged = checkNotNull(BubbleVisibility.settledBounds()) { "no bubble after the drag" }
        assertTrue("the drag moved it: $home -> $dragged", dragged != home)
        BubbleVisibility.forgetDraggedPlace()
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val back = BubbleVisibility.settledBounds()
        Log.i(TAG, "stays visible: default $home, dragged $dragged, after off and on $back")
        assertEquals("back at its default place", home, back)
    }

    /**
     * The chat row with the keyboard hidden after a drop (the field keeps focus): the bubble goes with the keyboard (no
     * bubble without a keyboard) and must come back, on the screen, when the field is tapped and the keyboard is up
     * again. What is wrong, or null.
     */
    private fun keyboardDownAndUp(width: Int, height: Int): String? {
        Device.uiDevice.pressBack()
        val hidden = Bubble.awaitState { it == null }
        Device.await(By.res(Device.HOST, "field")).click()
        Bubble.awaitState { it?.startsWith("Ready") == true }
        SystemClock.sleep(SETTLE_MS)
        val problem = check(width, height)
        Log.i(TAG, "stays visible: keyboard down (bubble ${hidden ?: "gone"}) and up -> ${problem ?: "ok"}")
        return if (hidden != null) "bubble still shown as '$hidden' with the keyboard down" else problem
    }

    /** What is wrong with the bubble now, or null when its node, window frame and pixels are on the screen. */
    private fun check(width: Int, height: Int): String? {
        val screen = Rect(0, 0, width, height)
        val node = BubbleVisibility.settledBounds()
        val window = BubbleWindows.bubble()
        val glyph = window?.frame?.let(BubbleWindows::glyphPixels)
        return when {
            node == null -> "no node (state ${Bubble.state()}, window $window)"
            !screen.contains(node) -> "node $node off the screen"
            window == null || !window.visible -> "window $window not visible"
            !screen.contains(window.frame) -> "window frame ${window.frame} off the screen"
            glyph == null || glyph < MIN_GLYPH_PIXELS -> "nothing drawn in ${window.frame} ($glyph glyph pixels)"
            BubbleWindows.tray() != null -> "snooze tray left: ${BubbleWindows.tray()}"
            else -> null
        }
    }

    private companion object {
        const val SEED = 20261117
        const val ROUNDS = 24
        const val SCREENS = 3
        const val KEYBOARD_CYCLE_SCREEN = 2
        const val ROUNDS_PER_SCREEN = 4
        const val SETTLE_MS = 1_200L
        const val DRAG_STEPS = 20

        /** Dark pixels in the middle of the frame below which the bubble's glyph counts as not drawn. */
        const val MIN_GLYPH_PIXELS = 40
    }
}
