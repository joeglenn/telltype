package net.jbglenn.telltype.platform.overlay

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/**
 * The root view of an overlay window: measures its one child against the whole display, whatever the window
 * offers. A wrap-content window is measured no wider than its current frame allows, so content that grows (the
 * Ready circle becoming the Listening pill) would widen the window only a few pixels per layout pass: the pill
 * took seconds to reach its width, with ✓ cut off and ✕ and ✓ moving under the finger meanwhile. The window
 * manager sizes the window from this view's measured size, so the window follows the content at once.
 *
 * It also tells accessibility services when the window moved or changed size: Compose sends no event when only
 * the bounds of its nodes change, so a client that fetched ✕ while the pill was growing would keep tapping where
 * ✕ was then. A subtree change event makes it fetch the nodes again. The check runs after every layout and before
 * every frame is drawn: the window frame can arrive a frame after the layout that asked for it (it moves a window
 * anchored to the right edge when it grows), and such a move alone lays nothing out.
 *
 * The display size is read at every measure (it changes on rotation).
 *
 * It also reports whether the window is at rest ([onAtRest]): its frame has the content's size, its left edge is
 * where [restingAt] says a window of that width belongs, and it has drawn two frames there since. After
 * the content changes size the frame follows a frame later or more, and the system reports accessibility bounds
 * against where the window's last frame put it (measured in a fresh process: the Fix chip 700 px right of where it
 * was drawn). Meanwhile buttons are reported, and taps land, beside where they end up, so the bubble keeps its
 * controls out of the accessibility tree until the window is at rest again.
 *
 * The window also counts as at rest only once the accessibility system's own record of the window frame
 * ([systemFrame], null when unknown) is where the window was asked to be, top included (board B3). The system
 * computes every node's screen bounds from that record, not from this process's view of the frame: after the
 * window grew from the circle to the pill this process already had the new left edge while the system still had
 * the circle's frame, and reported the Fix chip at x = 1652 on a 1080 px screen (left 891 + 761) for about 200 ms.
 *
 * Waiting for rest is bounded (A7h): when the window has not come to rest 500 ms after it left it and
 * after at least 30 frames (an offset this check does not foresee, a display cutout, a split-screen
 * frame), it stops asking for frames, reports at rest anyway so the controls are reachable, and calls
 * [onRestTimedOut] once (the owner logs a warning). A change of the content's size starts a new wait.
 * [uptimeMillis] is the clock (the main looper's, virtual in tests).
 *
 * The counting itself is [RestTracker]'s. [fingerDown] says whether a finger is on the window (between its touch
 * down and its up or cancel): the bubble's self-check never moves a bubble the user is touching.
 *
 * Touches are dispatched unbuffered: the window follows a dragging finger, and batched touches are resampled to
 * the frame time, which extrapolates a fast finger past where it stopped; when the finger then rests, the platform
 * rewrites the resting moves and the lift to that extrapolated point (28 px past the finger after a 705 px slide in
 * 40 ms on the emulator). Unbuffered, every sample arrives as it was reported.
 */
internal class OverlayRoot @JvmOverloads constructor(
    context: Context,
    private val restingAt: (width: Int) -> WindowPosition? = { null },
    private val onAtRest: (Boolean) -> Unit = {},
    private val onRestTimedOut: (numbers: String) -> Unit = {},
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
    private val systemFrame: () -> Rect? = { null },
) : FrameLayout(context) {
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val visible = Rect()
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private val rest = RestTracker(
        onAtRest = onAtRest,
        onRestTimedOut = { onRestTimedOut(restNumbers) },
        uptimeMillis = uptimeMillis,
        askForFrame = ::invalidate,
        beforeRest = ::refreshWindowPosition,
    )

    /**
     * The numbers the at-rest check compared, for the warning when it gave up (the Pixel on Android 17 logged a
     * timeout; the next log shows why): where [restingAt] expects the window, where it is on the screen, the visible
     * rect, the content's size, the display and the accessibility system's record of the frame.
     */
    private val restNumbers: String
        get() {
            val at = IntArray(LOCATION_SIZE).also(::getLocationOnScreen)
            val seen = Rect().takeIf(::getGlobalVisibleRect)
            return "expected ${restingAt(width)}, on screen ${at[0]},${at[1]}, visible $seen, size ${width}x$height, " +
                "screen ${windowManager.currentWindowMetrics.bounds}, system record ${systemFrame()}"
        }

    /** Whether a finger is on the window now (touch down seen, no up or cancel yet). Main thread. */
    var fingerDown: Boolean = false
        private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(0, 0)
            return
        }
        child.measure(atMost(width), atMost(height))
        setMeasuredDimension(child.measuredWidth, child.measuredHeight)
        if (child.measuredWidth != laidOutWidth || child.measuredHeight != laidOutHeight) rest.leave(newWait = true)
    }

    /**
     * Asks for unbuffered (unbatched, never resampled) touches from the first finger on; see the class comment.
     * Keeps [fingerDown].
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerDown = true
                requestUnbufferedDispatch(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDown = false
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * At most the display size, whatever the window offers: more than its current frame (content that grows), and
     * never more than the display (with `FLAG_LAYOUT_NO_LIMITS` the window manager offers the window up to
     * 100,000 px, and content that fills the width, like the snooze band, must not be laid out wider than the screen).
     */
    private fun atMost(display: Int): Int = MeasureSpec.makeMeasureSpec(display, MeasureSpec.AT_MOST)

    private val announced = IntArray(LOCATION_SIZE)
    private val location = IntArray(LOCATION_SIZE)

    private val beforeDraw = ViewTreeObserver.OnPreDrawListener {
        if (announceIfMoved(changed = false)) refreshWindowPosition()
        rest.frame(inPlace(), ::systemCaughtUp)
        true
    }

    /**
     * Lays the child out at its measured size in the top-left corner; announces new bounds to accessibility and
     * whether the window is at rest.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        getChildAt(0)?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        laidOutWidth = width
        laidOutHeight = height
        announceIfMoved(changed)
        if (!inPlace()) rest.leave()
    }

    /**
     * The window frame has the content's size (the visible rect is clipped to the frame) and its left edge is where
     * [restingAt] puts a window of this width. The top is not compared: after a move that only changes it, this
     * view's window top was measured stale for seconds (API 35) while the system had the window in place; the frames
     * counted by [RestTracker] give the system the time to apply it.
     */
    private fun inPlace(): Boolean {
        val expected = restingAt(width) ?: return true
        getLocationOnScreen(location)
        val inFrame = getGlobalVisibleRect(visible) && visible.width() >= width && visible.height() >= height
        return inFrame && location[0] == expected.x
    }

    /**
     * Whether the accessibility system's record of the window frame is where the window belongs (left, top and
     * size); true when either is unknown.
     */
    private fun systemCaughtUp(): Boolean {
        val expected = restingAt(width)
        val recorded = expected?.let { systemFrame() }
        return expected == null || recorded == null ||
            (
                recorded.left == expected.x && recorded.top == expected.y && recorded.width() == width &&
                    recorded.height() == height
                )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(beforeDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(beforeDraw)
        fingerDown = false
        super.onDetachedFromWindow()
    }

    /**
     * Tells accessibility the bounds changed when laid out anew or moved. The window (its view root) sends the
     * event, throttled, and only while accessibility is on. Returns whether the window moved since the last call.
     */
    private fun announceIfMoved(changed: Boolean): Boolean {
        getLocationOnScreen(location)
        val moved = !location.contentEquals(announced)
        if (changed || moved) {
            location.copyInto(announced)
            parent?.notifySubtreeAccessibilityStateChanged(this, this, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        }
        return moved
    }

    /**
     * The window moved without a layout pass (board B3): a window anchored to the right edge that grows gets its
     * new left edge from the window manager a traversal after the layout that grew it, and that traversal lays
     * nothing out. Compose caches the window's screen position at layouts and global-layout callbacks only, and
     * reports its accessibility nodes against that cache: the Fix chip was reported 714 px right of where it was
     * drawn (against the frame the window had as a circle), while this view's own position, and so the at-rest
     * check, was already right. The global-layout callback makes Compose read the position again.
     */
    private fun refreshWindowPosition() {
        viewTreeObserver.takeIf { it.isAlive }?.dispatchOnGlobalLayout()
    }

    private companion object {
        /** x and y. */
        const val LOCATION_SIZE = 2
    }
}
