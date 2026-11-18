package net.jbglenn.telltype.e2e

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import net.jbglenn.telltype.e2e.Device.WAIT_MS
import net.jbglenn.telltype.e2e.Device.uiDevice

/**
 * The bubble as the error-table tests see it: its state description (what TalkBack announces), its buttons and
 * chips, and the debug switches (marker files in the app's `no_backup/debug`) that force fake engines.
 */
internal object Bubble {
    const val NAME = "Dictation bubble"
    private const val POLL_MS = 100L
    private const val DEBUG_DIR = "debug"

    /** A case-insensitive exact match for UI Automator (buttons render in caps). */
    fun text(value: String): Pattern = Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE)

    /** The bubble's node in the overlay window, or null when no bubble is shown. */
    fun node(): AccessibilityNodeInfo? = windowRoot()?.let(::find)

    /** The root of the overlay window holding the bubble (with its chips), or null when no bubble is shown. */
    private fun windowRoot(): AccessibilityNodeInfo? {
        val automation = Device.instrumentation.getUiAutomation(
            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
        )
        return automation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .mapNotNull { it.root }
            .firstOrNull { find(it) != null }
    }

    private fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        firstNode(node) { it.contentDescription?.toString() == NAME }

    /** The first node at or below [node] that matches [predicate], depth first. */
    private fun firstNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = if (predicate(node)) {
        node
    } else {
        (0 until node.childCount).asSequence().mapNotNull(node::getChild)
            .firstNotNullOfOrNull { firstNode(it, predicate) }
    }

    /**
     * The bounds the accessibility node of the bubble's button [label] reports (what TalkBack and UI Automator tap),
     * null while there is no such button.
     */
    fun controlBounds(label: String): Rect? =
        windowRoot()?.let { findLabel(it, label) }?.let { button -> Rect().also(button::getBoundsInScreen) }

    private fun findLabel(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? = firstNode(node) {
        (it.contentDescription?.toString() ?: it.text?.toString()).equals(label, ignoreCase = true)
    }

    /** Clicks the button [label] through its accessibility action (as TalkBack's double tap does). */
    fun actionClick(label: String) {
        var button = windowRoot()?.let { findLabel(it, label) }
        while (button != null && !button.isClickable) button = button.parent
        check(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "could not click '$label'" }
    }

    /** Taps the centre of what the node of [label] reports, as soon as it exists; returns the bounds tapped. */
    fun tapControl(label: String, limit: Long = WAIT_MS): Rect {
        val bounds = control(label, limit)
        uiDevice.click(bounds.centerX(), bounds.centerY())
        return bounds
    }

    /** The bubble's state description ("Ready", "Listening", "Problem. Didn't catch that"...), null when hidden. */
    fun state(): String? = node()?.stateDescription?.toString()

    /** The bubble's bounds on screen, null when hidden. */
    fun bounds(): Rect? = node()?.let { node -> Rect().also(node::getBoundsInScreen) }

    /** Waits until the state description matches [predicate]; returns the last state seen. */
    fun awaitState(limit: Long = WAIT_MS, predicate: (String?) -> Boolean): String? {
        val deadline = SystemClock.uptimeMillis() + limit
        var last = state()
        while (!predicate(last) && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
            last = state()
        }
        return last
    }

    /** Waits for a state starting with [prefix] and fails with the last state seen. */
    fun expectState(prefix: String, limit: Long = WAIT_MS): String {
        val last = awaitState(limit) { it?.startsWith(prefix) == true }
        check(last?.startsWith(prefix) == true) { "bubble state '$prefix' expected within $limit ms, saw '$last'" }
        return checkNotNull(last)
    }

    /** The bubble itself (the mic in Ready). */
    fun await(): UiObject2 = Device.await(By.desc(NAME), what = "the bubble")

    /**
     * Waits for a button or chip of the bubble by its label or description (case-insensitive) and returns its
     * bounds. Searches the overlay window's nodes directly: UI Automator's selectors do not see the chips.
     */
    fun control(label: String, limit: Long = WAIT_MS): Rect {
        val deadline = SystemClock.uptimeMillis() + limit
        var bounds = controlBounds(label)
        while (bounds == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
            bounds = controlBounds(label)
        }
        return checkNotNull(bounds) { "not found within $limit ms: the bubble's '$label'" }
    }

    /** Taps the mic and waits for Listening. */
    fun startListening() {
        await().click()
        expectState("Listening")
    }

    /** Switches a debug marker file on or off. */
    fun marker(name: String, on: Boolean, content: String = "on\n") {
        val file = File(Device.app.noBackupFilesDir, "$DEBUG_DIR/$name")
        if (on) {
            file.parentFile?.mkdirs()
            file.writeText(content)
        } else {
            file.delete()
        }
    }

    /** The fake speech engine and cleaner of the debug build (no server). */
    fun fakeEngines(on: Boolean) = marker("fake-engines", on)

    /** The debug build's scripted faults for the fake engines (`stt=…`, `cleanup=…` lines), or none. */
    fun faults(vararg lines: String) =
        marker("fake-faults", lines.isNotEmpty(), lines.joinToString("\n", postfix = "\n"))
}
