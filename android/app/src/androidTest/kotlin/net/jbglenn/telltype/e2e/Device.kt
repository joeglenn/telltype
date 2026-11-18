package net.jbglenn.telltype.e2e

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.service.TelltypeAccessibilityService

/**
 * The uiDevice side of the end-to-end tests: the installed debug app (this test runs in its process), UI Automator
 * with other accessibility services kept connected, shell commands, and screenshots to `/data/local/tmp/owk-e2e`.
 */
internal object Device {
    const val HOST = "net.jbglenn.telltype.testhost"
    const val TAG = "OwkE2E"
    const val WAIT_MS = 15_000L
    private const val SHOTS = "/data/local/tmp/owk-e2e"
    private const val POLL_MS = 200L
    private const val SHORT_WAIT_MS = 2_000L
    private const val IDLE_WAIT_MS = 500L

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val app: Context get() = instrumentation.targetContext

    val uiDevice: UiDevice by lazy {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        // The listening bubble animates and announces its timer, so the UI is never idle: without a short limit
        // every click first waits the default 10 s for idle, and the recording runs that much longer.
        Configurator.getInstance().waitForIdleTimeout = IDLE_WAIT_MS
        UiDevice.getInstance(instrumentation)
    }

    /** The UI automation that keeps the app's accessibility service running (shell, screenshots, injected touches). */
    val uiAutomation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    /** Runs [command] as the shell user and returns its output (never logged). */
    fun shell(command: String): String {
        val output = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
    }

    /** Switches the app's accessibility service on through `settings`, as the brief allows, and waits for it. */
    fun enableService() {
        uiDevice
        if (AccessibilityServiceBridge.shared.connected.value) return
        val component = "${app.packageName}/${TelltypeAccessibilityService::class.java.name}"
        // After a reinstall the setting may still name the service while it is unbound; writing the same value
        // changes nothing, so clear it first.
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!AccessibilityServiceBridge.shared.connected.value && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
        }
        check(AccessibilityServiceBridge.shared.connected.value) { "the accessibility service did not connect" }
    }

    /** Waits for [selector] and returns the object. */
    fun await(selector: BySelector, limit: Long = WAIT_MS, what: String = selector.toString()): UiObject2 =
        checkNotNull(uiDevice.wait(Until.findObject(selector), limit)) { "not found within $limit ms: $what" }

    /** Like [await], scrolling the first scrollable container down while [selector] is not on screen. */
    fun awaitScrolling(selector: BySelector): UiObject2 {
        uiDevice.wait(Until.findObject(selector), SHORT_WAIT_MS)?.let { return it }
        val container = await(By.scrollable(true), what = "a scrollable screen")
        return container.scrollUntil(Direction.DOWN, Until.findObject(selector)) ?: await(selector)
    }

    /** Opens a fresh test host screen with [fieldType] and taps its field, so the keyboard comes up. */
    fun openHostField(fieldType: String): UiObject2 {
        uiDevice.pressHome()
        uiDevice.wait(Until.gone(By.pkg(HOST)), WAIT_MS)
        val intent = Intent().setClassName(HOST, "$HOST.ViewFieldActivity")
            .putExtra("field_type", fieldType)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        val field = await(By.res(HOST, "field"))
        field.click()
        return field
    }

    /**
     * Opens the test host's chat screen (message field at the bottom, send button beside it) and taps its field, so
     * the keyboard comes up.
     */
    fun openChatField(): UiObject2 {
        uiDevice.pressHome()
        uiDevice.wait(Until.gone(By.pkg(HOST)), WAIT_MS)
        val intent = Intent().setClassName(HOST, "$HOST.ChatFieldActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        app.startActivity(intent)
        val field = await(By.res(HOST, "field"))
        field.click()
        return field
    }

    /** The app's own screen (MainActivity), brought to front. */
    fun openApp() {
        val intent = checkNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        uiDevice.wait(Until.hasObject(By.pkg(app.packageName).depth(0)), WAIT_MS)
    }

    /**
     * Types [text] into the [index]th editable field of the window in front through the accessibility action
     * (UI Automator's own `setText` may log the text; this one is for the server key).
     */
    fun setTextQuietly(index: Int, text: String) {
        val root = checkNotNull(uiAutomation.rootInActiveWindow) { "no active window" }
        val editable = mutableListOf<AccessibilityNodeInfo>()
        collectEditable(root, editable)
        val field = editable.getOrNull(index) ?: error("only ${editable.size} editable fields")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) { "set text refused" }
    }

    private fun collectEditable(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) into += node
        for (child in 0 until node.childCount) node.getChild(child)?.let { collectEditable(it, into) }
    }

    /** Saves a screenshot as `/data/local/tmp/owk-e2e/<name>.png` (pulled by the host afterwards). */
    fun screenshot(name: String) {
        shell("mkdir -p $SHOTS")
        shell("screencap -p $SHOTS/$name.png")
        Log.i(TAG, "screenshot $name")
    }

    /** The recording files left in the app's private recordings directory. */
    fun recordings(): List<String> = File(app.noBackupFilesDir, "recordings").listFiles()?.map(File::getName).orEmpty()

    /** Waits until no recording file is left; returns what is left at the end. */
    fun awaitNoRecordings(limit: Long = WAIT_MS): List<String> {
        val deadline = SystemClock.uptimeMillis() + limit
        while (recordings().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        return recordings()
    }

    /** Switches the debug build's clip microphone on (the emulator's microphone is silent). */
    fun useClipMicrophone() {
        File(app.noBackupFilesDir, "debug/clip-microphone").apply { parentFile?.mkdirs() }.writeText("on\n")
    }
}
