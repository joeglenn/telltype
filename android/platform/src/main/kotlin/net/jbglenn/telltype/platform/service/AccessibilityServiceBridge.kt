package net.jbglenn.telltype.platform.service

import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The meeting point between [TelltypeAccessibilityService], which the system creates and binds, and the
 * adapters in the dependency graph. The service attaches itself when connected and detaches when unbound; it
 * forwards filtered events as [signals]. Android runs at most one instance of a service per process, so one bridge
 * per process ([shared]) is exact. Tests of the adapters make their own instances.
 *
 * Thread-safe: the service calls it on the main thread, the adapters read it from any thread.
 */
public class AccessibilityServiceBridge internal constructor() {
    private val current = MutableStateFlow<AccessibilityService?>(null)
    private val connectedState = MutableStateFlow(false)
    private val signalFlow = MutableSharedFlow<AccessibilitySignal>(
        extraBufferCapacity = SIGNAL_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The accessibility service is connected and bound. The coordinator starts when this turns true. */
    public val connected: StateFlow<Boolean> = connectedState.asStateFlow()

    /** The connected service, for the adapters that query windows and nodes; null while disconnected. */
    internal val service: StateFlow<AccessibilityService?> = current.asStateFlow()

    /** Filtered accessibility events. Hot; nothing is replayed. */
    internal val signals: SharedFlow<AccessibilitySignal> = signalFlow.asSharedFlow()

    /** Called by the service once the system has connected it. */
    internal fun attach(service: AccessibilityService) {
        current.value = service
        connectedState.value = true
    }

    /** Called by the service when it is unbound or destroyed; ignored for a service that is not the current one. */
    internal fun detach(service: AccessibilityService) {
        if (current.compareAndSet(service, null)) connectedState.value = false
    }

    /** Forwards [signal] without blocking; the oldest signals are dropped if nobody keeps up. */
    internal fun emit(signal: AccessibilitySignal) {
        signalFlow.tryEmit(signal)
    }

    public companion object {
        private const val SIGNAL_BUFFER = 64

        /** The process's bridge, used by the service and provided to the graph by `PlatformModule`. */
        public val shared: AccessibilityServiceBridge = AccessibilityServiceBridge()
    }
}
