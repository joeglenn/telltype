package net.jbglenn.telltype.platform.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityEventFilterTest {
    @Test
    fun `focus, window, windows and selection events ask for a focus reading`() {
        listOf(
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
        ).forEach { type ->
            val signal = AccessibilityEventFilter.signalFor(type, windowId = 4)
            assertEquals(AccessibilitySignal.FocusMayHaveChanged, signal)
        }
    }

    @Test
    fun `a text change carries its window`() {
        assertEquals(
            AccessibilitySignal.TextChanged(windowId = 4),
            AccessibilityEventFilter.signalFor(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, windowId = 4),
        )
    }

    @Test
    fun `other events are dropped`() {
        listOf(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
        ).forEach { type -> assertNull(AccessibilityEventFilter.signalFor(type, windowId = 4)) }
    }
}
