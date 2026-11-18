package net.jbglenn.telltype.platform.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.EditorInfo

/**
 * The Telltype accessibility service (docs/PLAN.md section 7.1, Appendix F). It does no work itself:
 * it attaches to the process's [AccessibilityServiceBridge] when connected, turns events into signals on the main
 * thread (filtering only), and detaches when unbound. Everything that queries nodes runs in the adapters on the
 * accessibility dispatcher.
 *
 * Created by the system, so it takes no injected dependencies; the same class runs in the app and in the
 * `:platform` instrumented tests.
 */
public class TelltypeAccessibilityService : AccessibilityService() {
    private val bridge: AccessibilityServiceBridge = AccessibilityServiceBridge.shared

    override fun onServiceConnected() {
        super.onServiceConnected()
        bridge.attach(this)
        bridge.emit(AccessibilitySignal.FocusMayHaveChanged)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        AccessibilityEventFilter.signalFor(event.eventType, event.windowId)?.let(bridge::emit)
        AccessibilityEventFilter.keyboardSignalFor(event.eventType, event.eventTime)?.let(bridge::emit)
    }

    override fun onInterrupt() {
        // No spoken or haptic feedback of its own to stop: the service only observes and types on request.
    }

    override fun onCreateInputMethod(): InputMethod = SignallingInputMethod(this, bridge)

    override fun onUnbind(intent: Intent?): Boolean {
        bridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        bridge.detach(this)
        super.onDestroy()
    }

    /**
     * The service's input method (Android 13 typing API). Starting or finishing input on a field changes the editor
     * information the focus reader uses (incognito flag, input type), so both send a focus signal.
     */
    private class SignallingInputMethod(service: AccessibilityService, private val bridge: AccessibilityServiceBridge) :
        InputMethod(service) {
        override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
            super.onStartInput(attribute, restarting)
            bridge.emit(AccessibilitySignal.FocusMayHaveChanged)
        }

        override fun onFinishInput() {
            super.onFinishInput()
            bridge.emit(AccessibilitySignal.FocusMayHaveChanged)
        }
    }
}
