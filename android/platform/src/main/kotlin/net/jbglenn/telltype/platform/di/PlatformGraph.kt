package net.jbglenn.telltype.platform.di

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.platform.context.AccessibilityFieldContextReader
import net.jbglenn.telltype.platform.edits.AccessibilityFieldEditWatcher
import net.jbglenn.telltype.platform.edits.TextChangeSwitch
import net.jbglenn.telltype.platform.field.AccessibilityGateway
import net.jbglenn.telltype.platform.field.FieldHandleRegistry
import net.jbglenn.telltype.platform.field.FieldResolver
import net.jbglenn.telltype.platform.focus.FocusReader
import net.jbglenn.telltype.platform.focus.FocusTracker
import net.jbglenn.telltype.platform.focus.KeyboardTracker
import net.jbglenn.telltype.platform.insert.AccessibilityTextInserter
import net.jbglenn.telltype.platform.insert.InsertionLadder
import net.jbglenn.telltype.platform.insert.LadderConfig
import net.jbglenn.telltype.platform.mic.MicServiceController
import net.jbglenn.telltype.platform.mic.MicServiceStatus
import net.jbglenn.telltype.platform.overlay.AccessibilityOverlayHost
import net.jbglenn.telltype.platform.overlay.OverlayTray
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.system.AndroidClipboard
import net.jbglenn.telltype.platform.system.AndroidFixLauncher
import net.jbglenn.telltype.platform.system.AndroidHaptics
import net.jbglenn.telltype.platform.system.AndroidNotifier
import net.jbglenn.telltype.platform.system.AndroidPermissionProbe
import net.jbglenn.telltype.platform.system.AndroidPermissionStatusSource
import net.jbglenn.telltype.platform.system.ClipboardManagerAccess
import net.jbglenn.telltype.platform.system.PermissionReader

/**
 * Every `:platform` adapter, wired once. [PlatformModule] exposes them to Hilt; the instrumented tests build the
 * same graph by hand around the real accessibility service. Everything that lives as long as the graph runs in
 * [scope] (the focus stream, the overlay's tracking, haptic cues).
 *
 * @param ladder the insertion ladder's tuning; tests restrict its steps.
 */
internal class PlatformGraph(
    context: Context,
    dispatchers: DispatcherProvider,
    logger: Logger,
    settings: SettingsRepository,
    scope: CoroutineScope,
    val bridge: AccessibilityServiceBridge = AccessibilityServiceBridge.shared,
    ladder: LadderConfig = LadderConfig(),
) {
    private val appContext: Context = context.applicationContext
    private val gateway = AccessibilityGateway(bridge, logger)
    private val registry = FieldHandleRegistry<AccessibilityNodeInfo>(copy = ::AccessibilityNodeInfo)

    /** Resolves the handles the [focusTracker] mints. */
    val resolver: FieldResolver = FieldResolver(registry, gateway)

    /** The system clipboard. */
    val clipboardManager: ClipboardManager = appContext.getSystemService(ClipboardManager::class.java)

    /** The focused field ([net.jbglenn.telltype.domain.port.FocusedFieldSource]). */
    val focusTracker: FocusTracker = FocusTracker(
        signals = bridge.signals,
        connected = bridge.connected,
        read = FocusReader(gateway, registry)::read,
        dispatchers = dispatchers,
        scope = scope,
    )

    /** Whether the keyboard is open for the focused field (the bubble goes with it). */
    private val keyboardTracker: KeyboardTracker = KeyboardTracker(
        signals = bridge.signals,
        connected = bridge.connected,
        readings = focusTracker.readings,
        keyboardShown = { withContext(dispatchers.accessibility) { FocusReader(gateway, registry).keyboardShown() } },
        logger = logger,
        scope = scope,
    )

    /** Context around the cursor. */
    val contextReader: AccessibilityFieldContextReader =
        AccessibilityFieldContextReader(resolver::resolve, dispatchers, scope)

    /** The insertion ladder. */
    val textInserter: AccessibilityTextInserter = AccessibilityTextInserter(
        resolve = resolver::resolve,
        ladder = InsertionLadder(ClipboardManagerAccess(clipboardManager, logger), ladder),
        settings = settings,
        dispatchers = dispatchers,
        logger = logger,
    )

    /** Text-changed events for the learning window. */
    val textChangeSwitch: TextChangeSwitch = TextChangeSwitch { on -> switchTextChanges(gateway, on) }

    /** Edits after an insertion. */
    val editWatcher: AccessibilityFieldEditWatcher =
        AccessibilityFieldEditWatcher(resolver::resolve, bridge.signals, textChangeSwitch, dispatchers)

    /** The bubble's window. */
    val overlayHost: AccessibilityOverlayHost =
        AccessibilityOverlayHost(bridge, focusTracker.readings, keyboardTracker.open, dispatchers, scope, logger)

    /** The snooze band's window, shown while the bubble is dragged. */
    val overlayTray: OverlayTray = OverlayTray(bridge, dispatchers, logger)

    /** The microphone foreground service. */
    val micService: MicServiceController =
        MicServiceController(appContext, bridge, MicServiceStatus.shared, dispatchers, logger)

    /** Hand-off and Copy. */
    val clipboard: AndroidClipboard = AndroidClipboard(clipboardManager, dispatchers, logger)

    /** Haptic and sound cues. */
    val haptics: AndroidHaptics = AndroidHaptics(appContext, settings, scope, dispatchers, logger)

    /** Notifications. */
    val notifier: AndroidNotifier = AndroidNotifier(appContext, dispatchers)

    /** Settings deep links. */
    val fixLauncher: AndroidFixLauncher = AndroidFixLauncher(appContext, bridge, dispatchers, logger)

    /** Permissions and system switches. */
    val permissions: AndroidPermissionStatusSource = AndroidPermissionStatusSource(
        PermissionReader(Build.VERSION.SDK_INT, AndroidPermissionProbe(appContext, bridge, logger)),
        bridge,
        dispatchers,
        scope,
    )

    init {
        // Nodes of a disconnected service cannot be refreshed; forget them so their handles resolve to "gone".
        scope.launch(dispatchers.default) { bridge.connected.filter { !it }.collect { registry.clear() } }
    }

    private fun switchTextChanges(gateway: AccessibilityGateway, on: Boolean) {
        gateway.withService(Unit) { service ->
            val info = service.serviceInfo
            info.eventTypes = if (on) {
                info.eventTypes or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            } else {
                info.eventTypes and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED.inv()
            }
            service.serviceInfo = info
        }
    }
}
