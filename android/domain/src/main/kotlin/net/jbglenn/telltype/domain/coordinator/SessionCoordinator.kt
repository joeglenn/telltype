package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * The only stateful piece of the dictation pipeline (docs/PLAN.md sections 6.3, 9 and 10; `android/docs/contracts.md`
 * page 9). It feeds every [SessionEvent] through the pure reducer `reduce`, strictly one at a time in arrival order,
 * publishes the next state, runs the effects the reducer returned against the ports, and feeds each effect's reply
 * back as the event the contracts name. It decides nothing the reducer decides.
 */
public interface SessionCoordinator {
    /** The bubble's state; [SessionState.Hidden] until the focus feed reports a field. */
    public val state: StateFlow<SessionState>

    /** One-off chips from `ShowChip`: hot and not replayed, so a chip nobody collects is dropped. */
    public val chips: Flow<Chip>

    /**
     * Queues [event] (overlay gestures and chip taps). Safe from any thread; never blocks and never throws. Events
     * queued before [start] are processed once it runs; after [close] they are ignored.
     */
    public fun dispatch(event: SessionEvent)

    /**
     * Starts the coordinator: finds the recordings a crash left behind (before any session can start), then runs
     * the event loop, the focus feed and the interruption feed. Calling it again does nothing.
     */
    public fun start()

    /**
     * Stops everything: every session, timer and watcher is cancelled and the microphone service is stopped. A
     * recording in progress is stopped and left on disk, so the next start recovers it into the pending queue.
     * Nothing keeps running afterwards. Calling it again does nothing.
     */
    public fun close()
}
