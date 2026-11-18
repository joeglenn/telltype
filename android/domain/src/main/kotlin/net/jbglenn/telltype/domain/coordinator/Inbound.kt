package net.jbglenn.telltype.domain.coordinator

import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionTimer

/**
 * What the event loop receives. Most events go straight to the reducer; replies the reducer cannot attribute
 * itself carry the coordinator's ticket so a stale one is dropped before it is mistaken for a current one.
 */
internal sealed interface Inbound {
    /** An overlay gesture, a focus or interruption change, or a `StageResult` (the reducer checks its session). */
    data class External(val event: SessionEvent) : Inbound

    /**
     * `RecordingStarted` or `RecordingFailed` for the start of session [start]. `Arming` has no session id yet, so
     * only the coordinator knows whether this start is still the one the bubble is arming for.
     */
    data class StartReply(val start: SessionId, val event: SessionEvent) : Inbound

    /** A timer ran out; only the latest scheduling of [timer] ([generation]) may fire. */
    data class TimerExpired(val timer: SessionTimer, val generation: Long) : Inbound
}
