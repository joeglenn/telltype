package net.jbglenn.telltype.platform.mic

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the microphone service is, as reported by the service itself. */
internal sealed interface MicServiceState {
    /** Not running (never started, stopped, or destroyed). */
    data object Stopped : MicServiceState

    /** A start was requested and the service has not answered yet. */
    data object Starting : MicServiceState

    /** In the foreground with type microphone: the process may record. */
    data object Running : MicServiceState

    /**
     * The system refused the foreground start.
     *
     * @property reason the exception's class name, for the log (no message: it may name other apps).
     */
    data class Refused(val reason: String) : MicServiceState
}

/**
 * The process-wide link between [MicService], which the system creates, and [MicServiceController]. One per
 * process ([shared]), like the service itself.
 */
internal class MicServiceStatus {
    private val state = MutableStateFlow<MicServiceState>(MicServiceState.Stopped)

    /** The latest state. */
    val current: StateFlow<MicServiceState> = state.asStateFlow()

    /** Sets the state. */
    fun report(newState: MicServiceState) {
        state.value = newState
    }

    companion object {
        /** The process's status. */
        val shared: MicServiceStatus = MicServiceStatus()
    }
}
