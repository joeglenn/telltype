package net.jbglenn.telltype.e2e

import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import kotlin.math.abs
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import net.jbglenn.telltype.core.model.settings.MicIcon
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The idle bubble's look (board U9, decision D56) on the device: each chosen look changes the Ready bubble's fill as
 * drawn on the screen, and the Listening pill keeps its own colour whatever the Ready bubble looks like. Screenshots
 * `a9b-ready-*` and `a9b-settings-bubble` in `/data/local/tmp/owk-e2e`. The look goes back to the default afterwards
 * (other tests find the glyph by its dark pixels).
 */
@RunWith(AndroidJUnit4::class)
class BubbleLookTest {
    @get:Rule
    val shots = Scenario.failureShots

    private val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
        Bubble.faults()
    }

    @After
    fun restoreTheDefaultLook() {
        Arrange.settings { it.copy(bubble = it.bubble.copy(look = BubbleLook.Default)) }
    }

    @Test
    fun eachLookColoursTheReadyBubbleAndListeningKeepsItsColour() {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val ready = mutableMapOf<String, Int>()
        val listening = mutableMapOf<String, Int>()
        for ((name, look) in Looks) {
            Arrange.settings { it.copy(bubble = it.bubble.copy(look = look)) }
            SystemClock.sleep(SETTLE_MS)
            Bubble.expectState("Ready")
            ready[name] = fillColour(sideOfGlyph = true)
            Device.screenshot("a9b-ready-$name")
            Bubble.startListening()
            SystemClock.sleep(SETTLE_MS)
            listening[name] = fillColour(sideOfGlyph = false)
            Bubble.control(CANCEL)
            Bubble.actionClick(CANCEL)
            Bubble.expectState("Ready")
        }
        val readyHex = ready.mapValues { hex(it.value) }
        Log.i(TAG, "bubble look: ready $readyHex, listening ${listening.mapValues { hex(it.value) }}")
        assertEquals("three different Ready fills: $ready", Looks.size, ready.values.map(::hex).toSet().size)
        val first = listening.values.first()
        assertTrue("Listening keeps its colour: $listening", listening.values.all { close(it, first) })
    }

    @Test
    fun settingsShowsTheBubbleLook() {
        Device.openApp()
        Device.await(By.text("Settings")).click()
        Device.awaitScrolling(By.text("Microphone icon"))
        SystemClock.sleep(SETTLE_MS)
        Device.screenshot("a9b-settings-bubble")
    }

    /** The bubble's fill on the screen now: beside the glyph (Ready) or near the pill's top edge (Listening). */
    private fun fillColour(sideOfGlyph: Boolean): Int {
        val bounds = checkNotNull(Bubble.bounds()) { "no bubble" }
        val x = if (sideOfGlyph) bounds.centerX() - (GLYPH_CLEARANCE_DP * density).toInt() else bounds.centerX()
        val y = if (sideOfGlyph) bounds.centerY() else bounds.centerY() - (PILL_EDGE_DP * density).toInt()
        val shot = checkNotNull(Device.uiAutomation.takeScreenshot()) { "no screenshot" }
        val pixel = shot.getPixel(x, y)
        shot.recycle()
        return pixel
    }

    private fun hex(colour: Int) = "#%06X".format(colour and 0xFFFFFF)

    private fun close(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) <= TOLERANCE &&
        abs(Color.green(a) - Color.green(b)) <= TOLERANCE &&
        abs(Color.blue(a) - Color.blue(b)) <= TOLERANCE

    private companion object {
        val Looks = linkedMapOf(
            "teal-ring" to BubbleLook(BubbleColour.Preset(BubblePreset.TEAL), transparency = 0, icon = MicIcon.RING),
            "sand-bold" to BubbleLook(BubbleColour.Preset(BubblePreset.SAND), transparency = 30, icon = MicIcon.BOLD),
            "custom-wave" to BubbleLook(BubbleColour.Custom(hue = 300, lightness = 35), 60, MicIcon.WAVE),
        )
        const val SETTLE_MS = 1_200L
        const val CANCEL = "Cancel"
        const val GLYPH_CLEARANCE_DP = 17
        const val PILL_EDGE_DP = 17
        const val TOLERANCE = 6
    }
}
