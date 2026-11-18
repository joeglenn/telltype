package net.jbglenn.telltype.domain.port

import kotlinx.coroutines.flow.StateFlow
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.system.BubbleDrop
import net.jbglenn.telltype.core.model.system.BubblePlacement

/**
 * Position and drag handling of the bubble's overlay window (docs/PLAN.md sections 5.2 and 7.2). Implemented by
 * `:platform`'s overlay host, which also shows the bubble's Compose content; the bubble in `:ui:overlay` calls
 * the drag methods from its gesture handlers. All methods are called on the main thread.
 */
public interface BubbleWindowPort {
    /** Where the bubble window is now; null while no bubble is shown or while it is hidden by the system state. */
    public val placement: StateFlow<BubblePlacement?>

    /** Moves the bubble by ([dx], [dy]) screen pixels while the finger is down. Ignored when nothing is shown. */
    public fun dragBy(dx: Float, dy: Float)

    /** The finger was lifted: snaps to the nearer edge (or reports the snooze zone) and returns which. */
    public fun dragEnded(): BubbleDrop
}

/**
 * The foreground service of type microphone that keeps the process allowed to record (section 7.3). Implemented
 * by `:platform`; started on demand from the accessibility service, stopped when processing ends. It does not
 * record: `:audio` opens the recorder once [start] succeeded.
 */
public interface MicrophoneServicePort {
    /**
     * Starts the service in the foreground, with its notification, and returns once it is or within a timeout.
     * Fails with [MicrophonePermissionMissing] without the permission and with [RecorderFailed] when the system
     * refuses the start or the service does not come up in time. Starting a running service succeeds at once.
     */
    public suspend fun start(): Outcome<Unit, CaptureError>

    /** Stops the service and removes its notification. Stopping a stopped service does nothing. */
    public suspend fun stop()
}
