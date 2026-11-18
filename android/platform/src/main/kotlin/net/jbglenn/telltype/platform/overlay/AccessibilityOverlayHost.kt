package net.jbglenn.telltype.platform.overlay

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.debug
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.AccessibilityDisabled
import net.jbglenn.telltype.core.model.error.PermissionError
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.system.BubbleDrop
import net.jbglenn.telltype.core.model.system.BubblePlacement
import net.jbglenn.telltype.domain.port.BubbleWindowPort
import net.jbglenn.telltype.platform.focus.FocusReading
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * Shows the bubble's Compose content in an accessibility-overlay window (docs/PLAN.md section 7.2) and places it
 * from the focused field: docked to an edge, above the keyboard, never over the field. The content comes from
 * `:ui:overlay` through `:app` as a composable lambda; this host owns only the window, the position and the drag
 * (where the bubble goes is [BubblePosition]'s).
 *
 * The window is hidden while the screen is locked or a permission dialog is in front, and removed when the
 * accessibility service disconnects. When the field is lost mid-session the bubble keeps its last place. A drag
 * snaps to the nearer edge and remembers its height per orientation.
 *
 * [show] and [hide] switch to the main thread; [dragBy] and [dragEnded] must be called on it (from gestures).
 *
 * The next time the bubble is gone it explains itself (board B1): every window opened and removed, every shown or
 * hidden switch, every placement that changes ([PlacementLogger]) and every drop is logged at info level (numbers
 * and sides only), and [BubbleSelfCheck] reads the window's frame back after it was shown or moved and puts a shown
 * bubble that cannot be seen back at its default place, never while a finger is on it.
 *
 * The bubble fades in when it appears and out when it is hidden ([WindowFade], about 120 ms, none with the system's
 * animations removed); while it fades out it takes no touches, and a show during the fade-out reverses it in the
 * same window. A disconnected service removes the window at once.
 *
 * @param readings the focus tracker's readings (field, keyboard, blocked screen).
 * @param keyboard whether the keyboard is open for the focused field ([keyboardOpen]).
 * @param uptimeMillis the clock for a drag that never ended.
 */
public class AccessibilityOverlayHost internal constructor(
    private val bridge: AccessibilityServiceBridge,
    private val readings: StateFlow<FocusReading>,
    keyboard: StateFlow<Boolean>,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val logger: Logger,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) : BubbleWindowPort {
    private val placementState = MutableStateFlow<BubblePlacement?>(null)
    private val position = BubblePosition({ service?.resources?.configuration?.orientation ?: 0 }, uptimeMillis)
    private val placementLog = PlacementLogger(logger)
    private var window: OverlayWindow? = null
    private var service: AccessibilityService? = null
    private var tracking: Job? = null
    private var field: FieldSnapshot? = null
    private var blocked = false

    private val atRest = mutableStateOf(true)
    private val docked = mutableStateOf(BubbleSide.RIGHT)

    private val selfCheck = BubbleSelfCheck(
        scope = scope,
        dispatchers = dispatchers,
        logger = logger,
        condition = {
            window?.let { current ->
                BubbleCondition(
                    blocked = blocked,
                    shown = current.shown,
                    fingerDown = current.fingerDown,
                    dragging = position.dragged != null,
                    sinceLastDragMs = uptimeMillis() - position.lastDragAt,
                    frame = current.frame(),
                    screen = current.screen,
                )
            }
        },
        details = {
            val current = window
            "frame ${current?.frame()}, content ${current?.width}x${current?.height}, screen ${current?.screen}, " +
                "requested ${current?.position}, side ${position.side}, remembered top ${position.rememberedTop}"
        },
        placeAtDefault = {
            position.forget()
            relayout()
            val current = window
            placementState.value?.let { current?.moveTo(WindowPosition(it.x, it.y), force = true) }
        },
    )

    private val margin: Int get() = (EDGE_MARGIN_DP * (service?.resources?.displayMetrics?.density ?: 1f)).toInt()

    override val placement: StateFlow<BubblePlacement?> = placementState.asStateFlow()

    /**
     * True while a field has the focus and the keyboard is open for it (docs/PLAN.md section 5.1). It turns false in
     * the windows-changed event in which the keyboard went, without the focus reading's quiet period (board B7).
     */
    public val keyboardOpen: StateFlow<Boolean> = keyboard

    /**
     * Whether the bubble's window frame has caught up with its content ([OverlayRoot]): false while it grows,
     * shrinks or moves into place. The content hides its controls from accessibility meanwhile. Main thread.
     */
    public val windowAtRest: State<Boolean> get() = atRest

    /**
     * The edge the bubble is docked to now: the settings' side until the user drags it to the other edge. The
     * content follows it (it grows from that edge), so the two never disagree after a drag. Main thread.
     */
    public val dockedSide: State<BubbleSide> get() = docked

    /**
     * Shows [content] in the overlay (or replaces the content of the one already shown), docked to [side] unless the
     * user dragged it elsewhere in this orientation. Fails with [AccessibilityDisabled] while the service is not
     * connected.
     */
    public suspend fun show(side: BubbleSide, content: @Composable () -> Unit): Outcome<Unit, PermissionError> =
        withContext(dispatchers.main) {
            position.defaultSide = side
            if (window == null) docked.value = position.side
            window?.takeIf { it.fadingOut }?.let {
                it.fadeBackIn()
                logger.info(TAG) { "bubble window fade-out reversed" }
            }
            val shown = window?.also { it.setContent(content) } ?: open(content)
            relayout()
            selfCheck.restart()
            if (shown == null) Outcome.Err(AccessibilityDisabled) else Outcome.Ok(Unit)
        }

    /**
     * Fades the overlay window out and removes it; at once when [fade] is false (the bubble's owner is going away),
     * or when it is not shown.
     */
    public suspend fun hide(fade: Boolean = true) {
        withContext(dispatchers.main) {
            val current = window ?: return@withContext
            if (!fade) return@withContext close()
            if (current.fadingOut) return@withContext
            selfCheck.cancel()
            placementState.value = null
            if (current.shown) logger.info(TAG) { "bubble window fading out" }
            current.fadeOut { if (window === current) close() }
        }
    }

    /**
     * Forgets where the user dragged the bubble (every orientation) and any drag in progress, so the bubble goes
     * back to its default place: the user's way back when the bubble is lost (switching it off and on, Resume).
     * [reason] goes to the log.
     */
    public suspend fun resetPlacement(reason: String) {
        withContext(dispatchers.main) {
            logger.info(TAG) { "bubble place reset to the default: $reason" }
            position.forget()
            relayout()
        }
    }

    override fun dragBy(dx: Float, dy: Float) {
        val current = window ?: return
        position.dragBy(dx, dy, current.position, current.extent)
        relayout()
        selfCheck.scheduleIfIdle()
    }

    /**
     * Follows a dragging finger now at ([x], [y]) on the screen that went down at ([grabX], [grabY]) inside the
     * window ([FingerDrag]): the window stays under the finger however far it lags behind its requests. The finger,
     * not the window, decides the snooze zone. Main thread.
     */
    public fun dragFinger(x: Float, y: Float, grabX: Float, grabY: Float) {
        val current = window ?: return
        position.dragFinger(x, y, grabX, grabY, current.extent)
        relayout()
        selfCheck.scheduleIfIdle()
    }

    override fun dragEnded(): BubbleDrop {
        val current = window
        val where = position.dragged?.let { "at ${it.x},${it.y}" } ?: "without a drag position"
        val drop = position.drop(current?.extent)
        logger.info(TAG) { "bubble dropped $where (width ${current?.width ?: 0}): $drop" }
        relayout()
        selfCheck.schedule()
        return drop
    }

    private fun open(content: @Composable () -> Unit): OverlayWindow? {
        val connected = bridge.service.value ?: return null
        val created = OverlayWindow(
            connected,
            content,
            ::relayout,
            onAtRest = { rest ->
                if (atRest.value != rest) {
                    atRest.value = rest
                    logger.debug(TAG) { "bubble window ${if (rest) "at rest, frame ${window?.frame()}" else "moving"}" }
                }
            },
            onRestTimedOut = { numbers ->
                logger.warn(TAG, null) { "bubble window not at rest in time; controls shown anyway: $numbers" }
            },
            systemFrame = connected::recordedOverlayFrame,
            fades = true,
        )
        val attached = try {
            created.attach()
            true
        } catch (e: WindowManager.BadTokenException) {
            logger.warn(TAG, e) { "overlay window refused" }
            false
        } catch (e: IllegalStateException) {
            logger.warn(TAG, e) { "overlay window refused" }
            false
        }
        return created.takeIf { attached }?.also { opened ->
            logger.info(TAG) {
                "bubble window opened (side ${position.side}, remembered top ${position.rememberedTop})"
            }
            window = opened
            service = connected
            tracking = scope.launch(dispatchers.main) { follow(connected) }
        }
    }

    private suspend fun follow(connected: AccessibilityService) {
        combine(readings, bridge.service) { reading, current -> reading to current }.collect { (reading, current) ->
            if (current !== connected) {
                close()
            } else {
                blocked = reading.blocked
                reading.snapshot?.let { field = it }
                relayout()
            }
        }
    }

    private fun close() {
        tracking?.cancel()
        tracking = null
        selfCheck.cancel()
        window?.let {
            it.detach()
            logger.info(TAG) { "bubble window removed" }
        }
        window = null
        service = null
        position.cancelDrag()
        placementLog.forget()
        placementState.value = null
    }

    /**
     * Shows the window unless the screen is blocked or the content has no size yet, and puts it where it goes now
     * (kept on the display by [OverlayWindow.moveTo]); logs the changes.
     */
    private fun relayout() {
        val current = window?.takeUnless { it.fadingOut } ?: return
        val measured = current.width > 0 && current.height > 0
        val shown = !blocked && measured
        if (shown != current.shown) {
            logger.info(TAG) {
                if (shown) "bubble window shown" else "bubble window hidden (blocked $blocked, measured $measured)"
            }
        }
        current.setShown(shown)
        if (!shown) {
            placementState.value = null
            return
        }
        val extent = current.extent
        val applied = current.moveTo(position.target(extent, margin, field))
        if (docked.value != position.side) docked.value = position.side
        if (position.dragged == null) {
            placementLog.placed(
                PlacementLine(position.side, applied, extent, field?.keyboardBounds?.top, position.rememberedTop),
            )
        }
        placementState.value = position.placement(applied, extent)
    }

    private companion object {
        val TAG = LogTag("OverlayHost")

        /** Appendix H: 8 dp from the edge. */
        const val EDGE_MARGIN_DP = 8
    }
}
