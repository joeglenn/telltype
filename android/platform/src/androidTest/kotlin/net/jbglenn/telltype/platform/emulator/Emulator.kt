package net.jbglenn.telltype.platform.emulator

import android.app.UiAutomation
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.platform.di.PlatformGraph
import net.jbglenn.telltype.platform.field.FocusedFieldFinder
import net.jbglenn.telltype.platform.insert.AccessibilityTextInserter
import net.jbglenn.telltype.platform.insert.InsertionLadder
import net.jbglenn.telltype.platform.insert.LadderConfig
import net.jbglenn.telltype.platform.insert.LadderStep
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.service.TelltypeAccessibilityService
import net.jbglenn.telltype.platform.system.ClipboardManagerAccess
import net.jbglenn.telltype.testing.FakeSettingsRepository

/**
 * The process-wide test environment: the real accessibility service of this test APK, switched on through
 * `settings`, and one [PlatformGraph] wired to it with real dispatchers. UI Automator keeps other accessibility
 * services connected ([UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES]), otherwise it would disconnect ours.
 */
internal object Emulator {
    const val HOST = "net.jbglenn.telltype.testhost"
    private val waitLimit = 15.seconds

    val instrumentation = InstrumentationRegistry.getInstrumentation()

    val device: UiDevice by lazy {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        UiDevice.getInstance(instrumentation)
    }

    val uiAutomation: UiAutomation
        get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    val dispatchers: DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Main
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val accessibility: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { Thread(it, "test-accessibility") }.asCoroutineDispatcher()
        override val audio: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { Thread(it, "test-audio") }.asCoroutineDispatcher()
    }

    val logger: Logger = LogcatLogger
    val settings = FakeSettingsRepository()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val graph: PlatformGraph by lazy {
        PlatformGraph(instrumentation.targetContext, dispatchers, logger, settings, scope)
    }

    private val stepInserters = mutableMapOf<LadderStep, AccessibilityTextInserter>()

    /** An inserter that tries only [step], sharing the graph's handles. */
    fun inserterFor(step: LadderStep): AccessibilityTextInserter = stepInserters.getOrPut(step) {
        AccessibilityTextInserter(
            resolve = graph.resolver::resolve,
            ladder = InsertionLadder(
                ClipboardManagerAccess(graph.clipboardManager, logger),
                LadderConfig(steps = listOf(step)),
            ),
            settings = settings,
            dispatchers = dispatchers,
            logger = logger,
        )
    }

    /** Switches this APK's accessibility service on and waits until it is connected. */
    fun connectService() {
        device
        graph
        val service = TelltypeAccessibilityService::class.java.name
        val component = "${instrumentation.targetContext.packageName}/$service"
        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        runBlocking { withTimeout(waitLimit) { AccessibilityServiceBridge.shared.connected.first { it } } }
    }

    /** Runs [command] as the shell user and returns its output. */
    fun shell(command: String): String {
        val descriptor = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    /**
     * Opens a fresh test host screen for [type] with optional initial text and selection. The previous screen is
     * closed first (home, then wait until no host window is left), so nothing below can find the old field.
     */
    fun open(type: HostField, text: String? = null, selection: IntRange? = null) {
        device.pressHome()
        device.wait(Until.gone(By.pkg(HOST)), waitLimit.inWholeMilliseconds)
        val intent = Intent().setClassName(HOST, type.screen)
            .putExtra("field_type", type.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        text?.let { intent.putExtra("initial_text", it) }
        selection?.let { intent.putExtra("selection_start", it.first).putExtra("selection_end", it.last) }
        instrumentation.targetContext.startActivity(intent)
        val deadline = SystemClock.uptimeMillis() + waitLimit.inWholeMilliseconds
        while (type.find() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        checkNotNull(type.find()) {
            val editables = device.findObjects(type.selector()).size
            "the ${type.id} field did not appear ($editables matching nodes; windows: ${windowSummary()})"
        }
    }

    private fun windowSummary(): String = AccessibilityServiceBridge.shared.service.value?.windows
        ?.joinToString { "${it.type}:${it.title}:${if (it.isFocused) "focused" else "-"}" }
        .orEmpty()

    /** Starts the test host's activity [simpleName] in a new task, without waiting for anything. */
    fun startHost(simpleName: String) {
        val intent = Intent().setClassName(HOST, "$HOST.$simpleName")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        instrumentation.targetContext.startActivity(intent)
    }

    /** The test host's view with resource name [name] (`field`, `other_field`, `tap_target`, `close`). */
    fun hostObject(name: String, compose: Boolean = false): UiObject2 = checkNotNull(
        device.wait(
            Until.findObject(if (compose) By.res(name) else By.res(HOST, name)),
            waitLimit.inWholeMilliseconds,
        ),
    ) { "$name not found" }

    /** Waits until the focus tracker publishes a snapshot matching [predicate]. */
    fun awaitSnapshot(limit: Duration = waitLimit, predicate: (FieldSnapshot?) -> Boolean): FieldSnapshot? {
        val found = runBlocking { withTimeoutOrNull(limit) { Found(graph.focusTracker.focused.first(predicate)) } }
        checkNotNull(found) { "no matching snapshot within $limit; last: ${graph.focusTracker.readings.value}" }
        return found.snapshot
    }

    /** A snapshot that matched (which may be null), so that "timed out" can be told apart. */
    private class Found(val snapshot: FieldSnapshot?)

    /** The focused field's node, fresh from the app (cache cleared), as the test's own view of the field. */
    fun focusedNode(): AccessibilityNodeInfo? = AccessibilityServiceBridge.shared.service.value?.let { service ->
        service.clearCache()
        FocusedFieldFinder.find(service)
    }

    /**
     * Sets the focused field's selection through the service, as the test's own setup step. Views answer false when
     * the selection is already there or the field is empty, and Compose and WebView apply it a frame later, so the
     * result is awaited on the field instead.
     */
    fun select(start: Int, end: Int) {
        val node = checkNotNull(focusedNode()) { "no focused field" }
        val arguments = android.os.Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
        val deadline = SystemClock.uptimeMillis() + SELECTION_WAIT_MS
        var at = selectionNow()
        while (at != start to end && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_MS)
            at = selectionNow()
        }
        check(at == start to end) { "selection is $at, wanted ${start to end}" }
    }

    private fun selectionNow(): Pair<Int, Int>? = focusedNode()?.let { now ->
        val empty = now.isShowingHintText || now.text.isNullOrEmpty()
        if (empty) 0 to 0 else now.textSelectionStart to now.textSelectionEnd
    }

    /** The focused field's text as the service sees it now (null when it reports none). */
    fun focusedText(): String? = focusedNode()?.let { node ->
        node.refresh()
        if (node.isShowingHintText) "" else node.text?.toString()
    }

    /** Logs one row of the results table (no field content). */
    fun record(test: String, field: String, step: String, outcome: String) {
        Log.i(RESULT_TAG, "$test|$field|$step|$outcome")
    }

    private const val RESULT_TAG = "A5Result"
    private const val POLL_MS = 100L
    private const val SELECTION_WAIT_MS = 5_000L

    /** Writes the platform's log lines to logcat; messages never hold dictated text (they use Redacted). */
    private object LogcatLogger : Logger {
        override fun isEnabled(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String) {
            Log.println(level.priority(), tag.name, message() + (throwable?.let { " ($it)" } ?: ""))
        }

        private fun LogLevel.priority(): Int = when (this) {
            LogLevel.DEBUG -> Log.DEBUG
            LogLevel.INFO -> Log.INFO
            LogLevel.WARN -> Log.WARN
            LogLevel.ERROR -> Log.ERROR
        }
    }
}
