package net.jbglenn.telltype.ui.overlay.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import kotlin.coroutines.cancellation.CancellationException
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.MotionTokens
import net.jbglenn.telltype.ui.overlay.BubbleEvent

/**
 * Detects the bubble's press gestures (tap, hold, slide away, drag) and reports them through [onEvent]; the
 * decisions are [PressTracker]'s. Presses already consumed by a child (✕, ✓, chips) are left alone.
 *
 * @param startsSession read at finger down: whether this press starts a session.
 * @param holdOpensMenu read at finger down: whether a long press opens the bubble menu instead of hold-to-talk.
 * @param onPressedChange the finger went down (true) or up (false), for the touched look.
 */
internal suspend fun PointerInputScope.detectBubblePress(
    startsSession: () -> Boolean,
    holdOpensMenu: () -> Boolean,
    onPressedChange: (Boolean) -> Unit,
    onEvent: (BubbleEvent) -> Unit,
) {
    val cancelDistance = BubbleTokens.SlideCancelDistance.toPx()
    val holdMillis = MotionTokens.HoldThreshold.inWholeMilliseconds
    val startMillis = MotionTokens.StartThreshold.inWholeMilliseconds
    awaitEachGesture {
        val down = awaitFirstDown()
        val tracker = PressTracker(viewConfiguration.touchSlop, cancelDistance, startsSession(), holdOpensMenu())
        onPressedChange(true)
        val grab = currentEvent.positionInWindow(down)
        tracker.down(grab.x, grab.y).forEach(onEvent)
        try {
            val deadlines = PressDeadlines(down.uptimeMillis + startMillis, down.uptimeMillis + holdMillis)
            trackPress(down, tracker, deadlines, onEvent)
        } catch (cancelled: CancellationException) {
            tracker.cancel().forEach(onEvent)
            throw cancelled
        } finally {
            onPressedChange(false)
        }
    }
}

/** When a press reaches the start threshold and the hold threshold, in the pointer events' uptime. */
private data class PressDeadlines(val start: Long, val hold: Long)

private suspend fun AwaitPointerEventScope.trackPress(
    down: PointerInputChange,
    tracker: PressTracker,
    deadlines: PressDeadlines,
    onEvent: (BubbleEvent) -> Unit,
) {
    var lastUptime = down.uptimeMillis
    val downOnScreen = currentEvent.screenPosition(down)
    while (true) {
        val deadline = when {
            tracker.awaitingStart -> deadlines.start
            tracker.awaitingHold -> deadlines.hold
            else -> null
        }
        val event = if (deadline != null) {
            withTimeoutOrNull((deadline - lastUptime).coerceAtLeast(0L)) { awaitPointerEvent() }
        } else {
            awaitPointerEvent()
        }
        if (event == null) {
            deadline?.let { lastUptime = it }
            val timedOut = if (tracker.awaitingStart) tracker.startTimeout() else tracker.holdTimeout()
            timedOut.forEach(onEvent)
            continue
        }
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) {
            tracker.cancel().forEach(onEvent)
            return
        }
        lastUptime = change.uptimeMillis
        val finger = event.screenPosition(change)
        val offset = finger - downOnScreen
        if (change.changedToUpIgnoreConsumed()) {
            // The lift is where the drag ends; it may come without a move to its position before it.
            if (tracker.dragging) tracker.move(offset.x, offset.y, finger.x, finger.y).forEach(onEvent)
            tracker.up().forEach(onEvent)
            return
        }
        tracker.move(offset.x, offset.y, finger.x, finger.y).forEach(onEvent)
        if (tracker.dragging) change.consume()
    }
}

/**
 * Where [change]'s finger is in the hosting window: the platform event's own coordinates (the bubble's view fills
 * the window from its top-left corner). Without a platform event (never on a device) the position in the bubble.
 */
private fun PointerEvent.positionInWindow(change: PointerInputChange): Offset =
    motionEvent?.let { Offset(it.x, it.y) } ?: change.position

/**
 * Where [change]'s finger is on the screen: the raw position of the platform event (the bubble is a single-finger
 * control, so the first pointer). Without a platform event (never on a device) the position in the bubble.
 */
private fun PointerEvent.screenPosition(change: PointerInputChange): Offset =
    motionEvent?.let { Offset(it.rawX, it.rawY) } ?: change.position
