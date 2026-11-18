package net.jbglenn.telltype.platform.service

import android.view.accessibility.AccessibilityEvent

/** What an accessibility event means to the adapters, after filtering on the main thread. */
internal sealed interface AccessibilitySignal {
    /** Focus, a window, the keyboard or the selection may have changed: read the focused field again. */
    data object FocusMayHaveChanged : AccessibilitySignal

    /**
     * A field's text changed (only delivered while an edit watcher has switched these events on).
     *
     * @property windowId the window the changed field is in.
     */
    data class TextChanged(val windowId: Int) : AccessibilitySignal

    /**
     * The set of windows changed, so the keyboard may have appeared or gone (sent besides [FocusMayHaveChanged]).
     * Read at once, without the focus reading's quiet period: the bubble goes with the keyboard (board B7).
     *
     * @property eventUptimeMillis when the system raised the event (`AccessibilityEvent.getEventTime`, uptime).
     */
    data class KeyboardMayHaveChanged(val eventUptimeMillis: Long) : AccessibilitySignal
}

/**
 * The only work done in `onAccessibilityEvent` (docs/PLAN.md section 7.1): the event type and window id, both
 * plain fields of the event, become a signal or nothing. No node is touched here.
 */
internal object AccessibilityEventFilter {
    /** The signal for an event of [eventType] in window [windowId], or null for events nobody needs. */
    fun signalFor(eventType: Int, windowId: Int): AccessibilitySignal? = when (eventType) {
        AccessibilityEvent.TYPE_VIEW_FOCUSED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
        -> AccessibilitySignal.FocusMayHaveChanged

        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> AccessibilitySignal.TextChanged(windowId)

        else -> null
    }

    /** The keyboard signal for an event of [eventType] raised at [eventUptimeMillis], or null for other events. */
    fun keyboardSignalFor(eventType: Int, eventUptimeMillis: Long): AccessibilitySignal? =
        AccessibilitySignal.KeyboardMayHaveChanged(eventUptimeMillis)
            .takeIf { eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED }
}
