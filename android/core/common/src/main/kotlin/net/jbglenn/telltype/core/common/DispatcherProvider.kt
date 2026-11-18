package net.jbglenn.telltype.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The coroutine dispatchers code may use (docs/PLAN.md section 9). Always injected, never read from
 * `Dispatchers` directly, so tests can substitute a single virtual-time dispatcher.
 */
public interface DispatcherProvider {
    /** Main thread: UI state, the session reducer, accessibility callbacks. */
    public val main: CoroutineDispatcher

    /** Network and disk. Callers that need a bound take `io.limitedParallelism(n)`. */
    public val io: CoroutineDispatcher

    /** CPU-bound work: guards, diffing, encoding. */
    public val default: CoroutineDispatcher

    /**
     * One dedicated thread for accessibility node queries, context reads and insertion attempts. These are
     * blocking cross-process calls; a single thread keeps them strictly ordered (first submitted, first run).
     * Every call made on it still needs its own timeout.
     */
    public val accessibility: CoroutineDispatcher

    /**
     * One dedicated audio-priority thread for the `AudioRecord` read loop. Never shared with other work,
     * because the read blocks.
     */
    public val audio: CoroutineDispatcher
}
