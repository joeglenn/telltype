package net.jbglenn.telltype.platform.field

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.KeyguardManager
import android.graphics.Rect
import android.graphics.Region
import android.os.PowerManager
import android.text.InputType
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.focus.FieldKindClassifier
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * The accessibility queries the adapters share, against the connected service. Every method makes blocking
 * cross-process calls: call only on the accessibility thread. Android exceptions become the given fallback and a
 * warning; nothing throws.
 */
internal class AccessibilityGateway(private val bridge: AccessibilityServiceBridge, private val logger: Logger) {
    /** Runs [block] with the connected service; [fallback] when none is connected or a call failed. */
    fun <T> withService(fallback: T, block: (AccessibilityService) -> T): T {
        val service = bridge.service.value ?: return fallback
        return guarded(fallback) { block(service) }
    }

    /** Runs [block], turning the exceptions node and window calls throw on stale objects into [fallback]. */
    fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        logger.warn(TAG, e) { "accessibility call failed" }
        fallback
    } catch (e: SecurityException) {
        logger.warn(TAG, e) { "accessibility call refused" }
        fallback
    }

    /** The editable field with input focus in the focused window, or null. See [FocusedFieldFinder]. */
    fun focusedEditable(service: AccessibilityService): AccessibilityNodeInfo? = FocusedFieldFinder.find(service)

    /**
     * The keyboard's touchable area on screen, or null when no input-method window is shown. While the keyboard
     * slides in, its window reaches below the screen (seen on Android 15: bottom 3048 on a 2400 px screen); the part
     * off screen is cut away, and the settled position follows with the next windows-changed event.
     */
    fun keyboardBounds(service: AccessibilityService): ScreenRect? {
        val keyboard = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val screen = service.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        return keyboard?.let { window ->
            val region = Region()
            window.getRegionInScreen(region)
            val bounds = if (region.isEmpty) Rect().also(window::getBoundsInScreen) else region.bounds
            val visible = screen == null || bounds.intersect(screen)
            bounds.toScreenRect().takeIf { visible && it.bottom > it.top }
        }
    }

    /**
     * The screen must not show the bubble or offer a field: the device is locked or asleep, or a system permission
     * dialog has the focused window (docs/PLAN.md section 7.2).
     *
     * Since Android 14 the permission dialogs mark their content accessibility-data-sensitive, so a service that is
     * not an accessibility tool (decision D15) cannot read the window's root at all (seen on Android 15: root null,
     * only the title). A focused application window whose content is hidden from us is therefore treated as such a
     * dialog; nothing in it could be dictated into anyway.
     */
    fun screenBlocked(service: AccessibilityService): Boolean {
        val locked = service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        val asleep = service.getSystemService(PowerManager::class.java)?.isInteractive == false
        val focused = service.windows.firstOrNull { it.isFocused }
        val root = focused?.root
        val hidden = focused != null && root == null && focused.type == AccessibilityWindowInfo.TYPE_APPLICATION
        val dialog = root?.packageName?.toString() in PERMISSION_DIALOG_PACKAGES
        return locked || asleep || hidden || dialog
    }

    /** What the field is: kind, incognito flag and hint, from the node and (when it matches) the editor. */
    fun describe(service: AccessibilityService, node: AccessibilityNodeInfo): FieldDescription {
        val editor = editorFor(service, node)
        val kinds = listOfNotNull(
            FieldKindClassifier.classify(node.inputType, node.isPassword, node.isMultiLine),
            editor?.let { FieldKindClassifier.classify(it.inputType, isPassword = false, isMultiLine = false) },
        )
        val kind = KIND_PRECEDENCE.first { it in kinds }
        val hint = node.hintText?.toString()
            ?: node.text?.toString()?.takeIf { node.isShowingHintText }
            ?: editor?.hintText?.toString()
        return FieldDescription(kind, editor?.let { FieldKindClassifier.isIncognito(it.imeOptions) } ?: false, hint)
    }

    /** The accessibility input connection, when it serves [node] (focused, same app, input started). */
    fun inputConnectionFor(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ): InputMethod.AccessibilityInputConnection? {
        val serves = editorFor(service, node) != null && focusedEditable(service) == node
        return if (serves) service.inputMethod?.currentInputConnection else null
    }

    /** One reading of [node], refreshed first; null when the node is gone. */
    fun state(service: AccessibilityService, node: AccessibilityNodeInfo): FieldState? {
        if (!node.refresh()) return null
        // An empty EditText without a hint reports null text (not "") and selection -1 on API 35 (found by the A7b
        // end-to-end test: the ladder then had no plan). An editable, non-password node with no text and no cursor
        // beyond 0 is that empty field; anything else without text stays unreadable (null).
        val empty = "".takeIf { node.isEditable && !node.isPassword && node.textSelectionEnd <= 0 }
        return FieldState(
            text = node.text?.toString() ?: empty,
            selection = selectionOf(node),
            showingHint = node.isShowingHintText,
            kind = describe(service, node).kind,
            focused = focusedEditable(service) == node,
        )
    }

    /** [node]'s selection or cursor, null when it reports none. */
    fun selectionOf(node: AccessibilityNodeInfo): TextRange? {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return if (start >= 0 && end >= 0) TextRange(minOf(start, end), maxOf(start, end)) else null
    }

    /**
     * The editor information of the started input, when it belongs to [node]: same package, and the same input class
     * where the node reports one (a WebView reports fewer flags on the node than in its editor). The input method
     * reports only the current editor, which is the focused field's.
     */
    private fun editorFor(service: AccessibilityService, node: AccessibilityNodeInfo): EditorInfo? {
        val inputMethod = service.inputMethod?.takeIf { it.currentInputStarted }
        val editor = inputMethod?.currentInputEditorInfo
        val samePackage = editor?.packageName == node.packageName?.toString()
        val nodeClass = node.inputType and InputType.TYPE_MASK_CLASS
        val editorClass = (editor?.inputType ?: InputType.TYPE_NULL) and InputType.TYPE_MASK_CLASS
        val sameClass = nodeClass == InputType.TYPE_NULL || nodeClass == editorClass
        return editor?.takeIf { samePackage && sameClass }
    }

    private companion object {
        val TAG = LogTag("A11yGateway")

        /** The packages whose windows are system permission dialogs. */
        val PERMISSION_DIALOG_PACKAGES = setOf(
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
        )

        /** When the node and the editor disagree, the more protective kind wins. */
        val KIND_PRECEDENCE = listOf(FieldKind.PASSWORD, FieldKind.NUMERIC, FieldKind.MULTI_LINE, FieldKind.SINGLE_LINE)
    }
}

/**
 * What [AccessibilityGateway.describe] found. Holds the hint (on-screen text): [toString] prints its length only.
 *
 * @property kind the field's kind.
 * @property incognito the editor asked for no personalised learning.
 * @property hint the hint or placeholder text, null when none.
 */
internal data class FieldDescription(val kind: FieldKind, val incognito: Boolean, val hint: String?) {
    override fun toString(): String = "FieldDescription(kind=$kind, incognito=$incognito, hint=${hint?.length} chars)"
}

/** This rectangle as a [ScreenRect], with empty or inverted sides collapsed. */
internal fun Rect.toScreenRect(): ScreenRect = ScreenRect(left, top, maxOf(left, right), maxOf(top, bottom))
