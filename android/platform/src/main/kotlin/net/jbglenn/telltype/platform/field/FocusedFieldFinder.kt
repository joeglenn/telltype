package net.jbglenn.telltype.platform.field

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Finds the editable field with input focus in the focused window. Blocking: accessibility thread only.
 *
 * Two quirks seen on Android 15 are handled here. The service's node cache can still name the view that last sent a
 * focus event (a Compose host view), or a field of a window that has just closed, as the input focus; so when there
 * is an answer but it is not a focused editable field in the focused window, the cache is cleared and the question
 * asked again, once (a null answer already came from the app, so it is not retried). And a WebView answers for its
 * whole page, and a Compose host view at times for its whole tree, so below a focused view that is not editable the
 * focused editable node is searched for (at most [MAX_SEARCHED_NODES] nodes; in touch mode only those two hosts hold
 * input focus without being editable, so the search is rare).
 */
internal object FocusedFieldFinder {
    /** A web page's nodes searched for the focused field, at most. */
    private const val MAX_SEARCHED_NODES = 400

    /** The focused editable field, or null. */
    fun find(service: AccessibilityService): AccessibilityNodeInfo? {
        val focusedWindow = service.windows.firstOrNull { it.isFocused }?.id
        val first = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return accept(first, focusedWindow) ?: run {
            service.clearCache()
            service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { accept(it, focusedWindow) }
        }
    }

    private fun accept(focus: AccessibilityNodeInfo, focusedWindow: Int?): AccessibilityNodeInfo? {
        val field = if (focus.isEditable) focus.takeIf { it.isFocused } else focusedEditableBelow(focus)
        return field?.takeIf { focusedWindow == null || it.windowId == focusedWindow }
    }

    private fun focusedEditableBelow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque(listOf(root))
        var visited = 0
        var found: AccessibilityNodeInfo? = null
        while (found == null && queue.isNotEmpty() && visited < MAX_SEARCHED_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable && node.isFocused) found = node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return found
    }
}
