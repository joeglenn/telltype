package net.jbglenn.telltype.platform.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The accessibility system's record of the frame of this service's overlay window titled [title] (node bounds are
 * computed from it, board B3), or null when the system lists no such window. One call into the system: ask it only
 * while a window waits to come to rest, never on every frame.
 */
internal fun AccessibilityService.recordedOverlayFrame(title: String): Rect? = windows
    .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() == title }
    ?.let { Rect().also(it::getBoundsInScreen) }
