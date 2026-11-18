package net.jbglenn.telltype.e2e

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Collections
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The snooze tray is only ever drawn on the bottom edge (the owner saw a piece of it flash at the top of the screen
 * on the Pixel while dragging the bubble). The tray's window lives in this process: on every frame during 20 drags
 * towards either edge, a frame callback finds it among this process's windows and, while its content is shown,
 * checks that the window's bottom is the display's bottom.
 */
@RunWith(AndroidJUnit4::class)
class TrayPlacementTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
        Bubble.faults()
        BubbleVisibility.forgetDraggedPlace()
    }

    @Test
    fun theSnoozeTrayIsOnlyEverDrawnOnTheBottomEdge() {
        Device.openHostField("plain")
        Bubble.expectState("Ready")
        val monitor = TrayMonitor()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback(monitor) }
        val width = Device.uiDevice.displayWidth
        val height = Device.uiDevice.displayHeight
        repeat(DRAGS) { drag ->
            val from = checkNotNull(BubbleVisibility.settledBounds()) { "drag $drag: no bubble" }
            val toX = if (drag % 2 == 0) width / 10 else width * 9 / 10
            val finger = Finger.down(from.centerX(), from.centerY())
            finger.slideTo(toX, height * (3 + drag % 3) / 10, STEPS)
            finger.up()
            Bubble.expectState("Ready")
            SystemClock.sleep(SETTLE_MS)
        }
        instrumentation.runOnMainSync { monitor.running = false }
        Log.i(TAG, "tray placement: ${monitor.framesShown} frames with the tray shown, wrong: ${monitor.wrong}")
        assertTrue("the tray was never seen", monitor.framesShown > 0)
        assertTrue("the tray was drawn off the bottom edge: ${monitor.wrong}", monitor.wrong.isEmpty())
    }

    /** Checks the tray window on every frame (main thread). */
    private class TrayMonitor : Choreographer.FrameCallback {
        var running = true
        var framesShown = 0
        val wrong: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val location = IntArray(2)

        override fun doFrame(frameTimeNanos: Long) {
            WindowInspector.getGlobalWindowViews()
                .filter { (it.layoutParams as? WindowManager.LayoutParams)?.title?.toString() == TRAY_TITLE }
                .forEach { root ->
                    val content = (root as? ViewGroup)?.getChildAt(0)
                    if (root.isAttachedToWindow && content?.isShown == true && root.height > 0) {
                        framesShown++
                        root.getLocationOnScreen(location)
                        val bottom = root.context.getSystemService(WindowManager::class.java)
                            .currentWindowMetrics.bounds.bottom
                        if (location[1] + root.height != bottom) wrong += "top ${location[1]}, height ${root.height}"
                    }
                }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private companion object {
        const val DRAGS = 20
        const val STEPS = 12
        const val SETTLE_MS = 800L
        const val TRAY_TITLE = "Telltype snooze tray"
    }
}
