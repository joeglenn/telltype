package net.jbglenn.telltype.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review item "listening pill" (brief A7c section 0): 2 s after listening starts the pill is at least 168 × 48 dp
 * (Appendix H), ✕ and ✓ are inside the overlay window, and the nodes the accessibility tree reports are where the
 * window is. Screenshot `pill-2s` is compared with the golden `listening_light.png` by the host.
 */
@RunWith(AndroidJUnit4::class)
class ListeningPillTest {
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
    fun pillIsFullSizeWithBothButtonsTwoSecondsIn() {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        Bubble.startListening()
        SystemClock.sleep(STEADY_MS)
        Device.screenshot("pill-2s")
        val density = Device.app.resources.displayMetrics.density
        val window = overlayWindowBounds()
        val pill = checkNotNull(Bubble.bounds()) { "no bubble" }
        val cancel = checkNotNull(Bubble.controlBounds("Cancel")) { "no ✕" }
        val finish = checkNotNull(Bubble.controlBounds("Finish")) { "no ✓" }
        Log.i(TAG, "pill: window $window bubble $pill cancel $cancel finish $finish density $density")
        Bubble.actionClick("Cancel")
        assertTrue("pill ${pill.width()} px wide, < 168 dp", pill.width() >= (PILL_WIDTH_DP * density).toInt())
        assertTrue("pill ${pill.height()} px high, < 48 dp", pill.height() >= (PILL_HEIGHT_DP * density).toInt())
        assertTrue("✕ $cancel inside the window $window", window.contains(cancel))
        assertTrue("✓ $finish inside the window $window", window.contains(finish))
        assertTrue("✕ left of ✓", cancel.right <= finish.left)
    }

    private fun overlayWindowBounds(): Rect {
        val automation = Device.instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        val window = automation.windows.first {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.root?.let { _ -> Bubble.node() } != null
        }
        return Rect().also(window::getBoundsInScreen)
    }

    private companion object {
        const val STEADY_MS = 2_000L
        const val PILL_WIDTH_DP = 168
        const val PILL_HEIGHT_DP = 48
    }
}
