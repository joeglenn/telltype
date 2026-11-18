package net.jbglenn.telltype.platform.overlay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class OverlayRootTest {
    private companion object {
        const val FRAME_MS = 16L
        const val WAITING_FRAMES = 100
        const val TIMEOUT_MS = 500L
        const val MIN_WAITING_FRAMES = 10
    }

    /** A child that wants [wanted] pixels in each direction, within its constraints. */
    private class Wanting(private val wanted: Int) : View(ApplicationProvider.getApplicationContext()) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(resolveSize(wanted, widthMeasureSpec), resolveSize(wanted, heightMeasureSpec))
        }
    }

    private fun measured(child: View, offered: Int): Pair<Int, Int> {
        val root = OverlayRoot(ApplicationProvider.getApplicationContext())
        root.addView(child)
        val spec = MeasureSpec.makeMeasureSpec(offered, MeasureSpec.AT_MOST)
        root.measure(spec, spec)
        return root.measuredWidth to root.measuredHeight
    }

    @Test
    fun `touches still reach the content after asking for unbuffered dispatch`() {
        val seen = mutableListOf<Int>()
        val child = object : View(ApplicationProvider.getApplicationContext()) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                seen += event.actionMasked
                return true
            }
        }
        val root = OverlayRoot(ApplicationProvider.getApplicationContext())
        root.addView(child)
        val spec = MeasureSpec.makeMeasureSpec(100, MeasureSpec.EXACTLY)
        root.measure(spec, spec)
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)
            .forEach { action -> root.dispatchTouchEvent(MotionEvent.obtain(0L, 0L, action, 5f, 5f, 0)) }
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            seen,
        )
    }

    @Test
    fun `a window that never comes to rest stops redrawing after 500 ms and exposes its controls`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val reports = mutableListOf<Boolean>()
        var timeouts = 0
        var waitStart: Long? = null
        // The root expects the window at x = -1, where it never gets (an offset the check does not foresee).
        val root = OverlayRoot(
            activity,
            restingAt = { WindowPosition(-1, 0) },
            onAtRest = {
                reports += it
                if (!it && waitStart == null) waitStart = SystemClock.uptimeMillis()
            },
            onRestTimedOut = { timeouts++ },
        )
        root.addView(Wanting(100))
        activity.setContentView(root, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        val canvas = Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        // One frame 16 ms later: draws (clearing the dirty flag), then runs the pre-draw check; true when the root
        // asked for yet another frame.
        val frame = {
            root.draw(canvas)
            looper.idleFor(Duration.ofMillis(FRAME_MS))
            root.viewTreeObserver.dispatchOnPreDraw()
            root.isDirty
        }

        val start = checkNotNull(waitStart)
        val frames = List(WAITING_FRAMES) {
            val asked = frame()
            SystemClock.uptimeMillis() to asked
        }
        val (waiting, after) = frames.partition { (time, _) -> time < start + TIMEOUT_MS }
        assertTrue("redraws every frame while waiting", waiting.all { (_, asked) -> asked })
        assertTrue("it kept redrawing until then (${waiting.size} frames)", waiting.size >= MIN_WAITING_FRAMES)
        assertTrue("and then stopped", after.isNotEmpty())
        assertEquals("gave up after 500 ms: controls exposed", true, reports.last())
        // The frame that crosses the deadline may still carry the request of a traversal run inside it.
        assertEquals(
            "no frame asked for after that",
            emptyList<Pair<Long, Boolean>>(),
            after.drop(1).filter {
                it.second
            },
        )
        assertEquals("one warning", 1, timeouts)
    }

    @Test
    fun `the window is not at rest until the accessibility system's record of its frame has caught up`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val reports = mutableListOf<Boolean>()
        // Board B3: this process has the new frame, the system's record (node bounds come from it) still the old.
        var recorded: Rect? = Rect(891, 1328, 1059, 1496)
        val root = OverlayRoot(
            activity,
            restingAt = { WindowPosition(0, 0) },
            onAtRest = { reports += it },
            systemFrame = { recorded },
        )
        root.addView(Wanting(100))
        activity.setContentView(root, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        val canvas = Canvas(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        val frame = {
            root.draw(canvas)
            looper.idleFor(Duration.ofMillis(FRAME_MS))
            root.viewTreeObserver.dispatchOnPreDraw()
        }

        repeat(5) { frame() }
        assertEquals("not at rest while the record is the old frame", false, reports.last())
        recorded = Rect(0, 0, root.width, root.height)
        repeat(5) { frame() }
        assertEquals("at rest once it caught up", true, reports.last())
    }

    @Test
    fun `content that grew is measured at its size, not at the window's current one`() {
        // The window offers its current frame (the 168 px Ready circle); the pill wants 483 px.
        assertEquals(483 to 483, measured(Wanting(483), offered = 168))
    }

    @Test
    fun `content is still bounded by the display`() {
        val display = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        assertEquals(display.width() to display.height(), measured(Wanting(50_000), offered = 168))
    }

    @Test
    fun `a window that offers more than the display still lays its content out at most display-sized`() {
        // FLAG_LAYOUT_NO_LIMITS lets the window manager offer far more than the screen; content that fills the
        // width (the snooze band) must not be laid out wider than the display.
        val display = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        assertEquals(display.width() to display.height(), measured(Wanting(50_000), offered = 200_000))
    }

    @Test
    fun `content smaller than the offer keeps its own size`() {
        assertEquals(126 to 126, measured(Wanting(126), offered = 840))
    }

    /** Stands in for the window's view root, which turns these notifications into accessibility events. */
    private class RecordingParent : android.widget.FrameLayout(ApplicationProvider.getApplicationContext()) {
        val subtreeChanges = mutableListOf<Int>()

        override fun notifySubtreeAccessibilityStateChanged(child: View, source: View, changeType: Int) {
            subtreeChanges += changeType
        }
    }

    @Test
    fun `a layout with new bounds tells accessibility services to fetch the nodes again`() {
        val parent = RecordingParent()
        val root = OverlayRoot(ApplicationProvider.getApplicationContext())
        root.addView(Wanting(168))
        parent.addView(root)
        root.measure(0, 0)
        root.layout(0, 0, 168, 168)
        root.layout(0, 0, 168, 168)
        root.layout(0, 0, 483, 168)
        assertEquals(
            "one subtree change per new bounds",
            listOf(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE),
            parent.subtreeChanges,
        )
    }
}
