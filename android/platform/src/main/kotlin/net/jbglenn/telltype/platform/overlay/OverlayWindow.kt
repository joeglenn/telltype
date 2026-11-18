package net.jbglenn.telltype.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.platform.field.toScreenRect

/**
 * One accessibility-overlay window holding a `ComposeView` (docs/PLAN.md section 7.2): not focusable (the keyboard
 * stays up), not touch-modal and sized to its content (touches elsewhere pass through), positioned in screen
 * coordinates. Main thread only. [context] must be the accessibility service: its window token makes the type
 * allowed without "Display over other apps".
 *
 * @param onResized called after the content's size changed, so the owner can place the window again.
 * @param onAtRest called with whether the window frame has caught up with the content ([OverlayRoot]).
 * @param onRestTimedOut called when the window stayed out of rest too long and the root stopped waiting.
 * @param title the window's title in `dumpsys window windows` (the bubble's and the snooze tray's differ).
 * @param systemFrame the accessibility system's record of the frame of the window with this title, null when unknown
 *   ([OverlayRoot] waits for it before the window counts as at rest).
 * @param bottomEdge whether the window sits on the bottom edge (the snooze tray): anchored there by its gravity from
 *   the start, so it never needs a move and can never draw anywhere else, not even for the frame before a move
 *   applies (the owner saw a piece of the tray flash at the top of the Pixel's screen).
 * @param fades whether the content fades in when it appears and can fade out before removal ([WindowFade]).
 */
internal class OverlayWindow(
    context: Context,
    content: @Composable () -> Unit,
    onResized: () -> Unit,
    onAtRest: (Boolean) -> Unit = {},
    onRestTimedOut: (numbers: String) -> Unit = {},
    title: String = BUBBLE_TITLE,
    systemFrame: (title: String) -> Rect? = { null },
    bottomEdge: Boolean = false,
    fades: Boolean = false,
) {
    private val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    private val owner = OverlayViewTreeOwner()
    private val view = ComposeView(context)
    private var anchor = WindowAnchor(fromRight = false, x = 0)
    private val fade: WindowFade? = if (fades) WindowFade(view) else null
    private val root = OverlayRoot(
        context,
        ::restingAt,
        onAtRest,
        onRestTimedOut,
        systemFrame = { systemFrame(title) },
    ).apply { addView(view) }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (bottomEdge) Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.START
        // No window manager animation: the bubble animates itself, and while the system animated the window in,
        // its accessibility nodes reported places where ✕ and ✓ were not (taps from TalkBack and tests missed).
        windowAnimations = 0
        // Nor when it moves: a window anchored to the right edge moves its top-left corner whenever it widens (the
        // pill, a chip), and the system animated that move. Accessibility bounds and touches follow the animated
        // position, so ✕ and the Fix chip were reported up to 900 px beside where they ended up, for about a second
        // in a fresh process. Android 13 has no public switch for it; the at-rest check covers it there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setCanPlayMoveAnimation(false)
        fitInsetsTypes = 0
        this.title = title
    }

    init {
        view.isInvisible = true
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeViewModelStoreOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent(content)
        view.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) onResized()
        }
    }

    /** The content's measured width; 0 before the first layout and while hidden. */
    val width: Int get() = view.width

    /** The content's measured height; 0 before the first layout and while hidden. */
    val height: Int get() = view.height

    /** The current top-left corner. */
    val position: WindowPosition
        get() = WindowPosition(anchor.left(view.width, screen), params.y)

    /** Where a window of [width] belongs with the current anchor; null while it is not attached. */
    private fun restingAt(width: Int): WindowPosition? =
        if (root.isAttachedToWindow) WindowPosition(anchor.left(width, screen), params.y) else null

    /** The display area the window can use. */
    val screen: ScreenRect get() = windowManager.currentWindowMetrics.bounds.toScreenRect()

    /** The content's size and the display, for placing it. */
    val extent: WindowExtent get() = WindowExtent(view.width, view.height, screen)

    /** Whether a finger is on the window now ([OverlayRoot.fingerDown]). */
    val fingerDown: Boolean get() = root.fingerDown

    /** Whether the content is shown (visible and touchable). */
    val shown: Boolean get() = view.isVisible

    /** Whether the content is fading out before its removal ([fadeOut]); it takes no touches meanwhile. */
    val fadingOut: Boolean get() = fade?.fadingOut == true

    /**
     * The window's frame on the screen as this process last heard it from the window manager (read back, not the
     * requested position); null while it is not attached.
     */
    fun frame(): ScreenRect? {
        if (!root.isAttachedToWindow) return null
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        return ScreenRect(location[0], location[1], location[0] + root.width, location[1] + root.height)
    }

    /** Adds the window. Throws what `WindowManager.addView` throws (a dead token after the service went away). */
    fun attach() {
        owner.resume()
        windowManager.addView(root, params)
    }

    /** Replaces the Compose content. */
    fun setContent(content: @Composable () -> Unit) {
        view.setContent(content)
    }

    /**
     * Moves the window to [position] when it is not there already, anchored to the nearer screen edge
     * ([WindowAnchor]) so that it grows from the edge it is docked to without moving.
     *
     * A move alone is no layout pass, so Compose would keep the screen position it cached at the last layout and
     * report its accessibility nodes (✕, ✓) where the window was before (UI Automator and TalkBack's explore-by-touch
     * then hit the wrong spot). The layout request makes the traversal that applies the move also lay out.
     *
     * Whatever the caller computed, the window is kept on the display: with `FLAG_LAYOUT_NO_LIMITS` the window
     * manager places it anywhere within ±100,000 px, so a wrong position would put the bubble off screen, shown
     * but invisible (board B1). [force] applies the position even when it is the one requested last (the
     * self-check, when the frame read back is not where it was asked to be). Returns the position applied.
     */
    fun moveTo(position: WindowPosition, force: Boolean = false): WindowPosition {
        val display = screen
        val onScreen = BubblePlacer.clamp(position, view.width, view.height, display)
        val next = WindowAnchor.of(onScreen, view.width, display)
        if (!force && next == anchor && params.y == onScreen.y) return onScreen
        anchor = next
        params.gravity = Gravity.TOP or if (next.fromRight) Gravity.END else Gravity.START
        params.x = next.x
        params.y = onScreen.y
        if (root.isAttachedToWindow) windowManager.updateViewLayout(root, params)
        root.requestLayout()
        return onScreen
    }

    /**
     * Shows or hides the content. Hidden, it keeps its size (so it can be placed before it first appears) but is
     * invisible and takes no touches. Starts hidden.
     */
    fun setShown(shown: Boolean) {
        val flags = if (shown) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val appearing = shown && !view.isVisible
        view.isInvisible = !shown
        if (appearing) fade?.appear()
        setTouchable(flags)
    }

    /**
     * Fades the content out ([WindowFade]) and then calls [onFaded] (the owner removes the window); at once when it
     * does not fade or is not shown. The window takes no touches from now on.
     */
    fun fadeOut(onFaded: () -> Unit) {
        val current = fade
        if (current == null || !view.isVisible) {
            onFaded()
            return
        }
        setTouchable(params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        current.disappear(onFaded)
    }

    /** Turns a fade-out back into a fade-in: the same window stays, touchable again once the owner shows it. */
    fun fadeBackIn() {
        fade?.reverse()
    }

    private fun setTouchable(flags: Int) {
        if (flags != params.flags) {
            params.flags = flags
            if (view.isAttachedToWindow) windowManager.updateViewLayout(root, params)
        }
    }

    /** Removes the window and releases the composition and the owners. */
    fun detach() {
        fade?.cancel()
        windowManager.removeViewImmediate(root)
        view.disposeComposition()
        owner.destroy()
    }

    companion object {
        /** The bubble window's title. */
        const val BUBBLE_TITLE = "Telltype bubble"

        /** The snooze tray window's title. */
        const val TRAY_TITLE = "Telltype snooze tray"
    }
}
