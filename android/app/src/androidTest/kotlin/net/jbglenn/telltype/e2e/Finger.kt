package net.jbglenn.telltype.e2e

import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * One finger on the touch screen, injected through UI Automation so a test can stop with the finger still down
 * and measure where the dragged bubble is (UI Automator's swipe always lifts the finger at the end).
 */
internal class Finger private constructor(private var x: Float, private var y: Float) {
    private val downTime = SystemClock.uptimeMillis()

    /**
     * Slides to ([toX], [toY]) in [steps] moves [STEP_MS] apart, then rests there for [REST_MOVES] moves, as a real
     * finger keeps reporting when it stops.
     */
    fun slideTo(toX: Int, toY: Int, steps: Int) {
        val fromX = x
        val fromY = y
        for (step in 1..steps + REST_MOVES) {
            x = fromX + (toX - fromX) * minOf(step, steps) / steps
            y = fromY + (toY - fromY) * minOf(step, steps) / steps
            inject(MotionEvent.ACTION_MOVE)
            SystemClock.sleep(STEP_MS)
        }
    }

    /** Lifts the finger where it is. */
    fun up() = inject(MotionEvent.ACTION_UP)

    private fun inject(action: Int) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            check(automation.injectInputEvent(event, true)) { "touch event not injected" }
        } finally {
            event.recycle()
        }
    }

    companion object {
        /** About 200 moves per second, like a real finger's touch reports. */
        const val STEP_MS = 5L

        /** Moves on the spot at the end of a slide (about 50 ms). */
        const val REST_MOVES = 10

        private val automation: UiAutomation
            get() = Device.instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

        /** Puts a finger down at ([x], [y]). */
        fun down(x: Int, y: Int): Finger = Finger(x.toFloat(), y.toFloat()).also { it.inject(MotionEvent.ACTION_DOWN) }
    }
}
