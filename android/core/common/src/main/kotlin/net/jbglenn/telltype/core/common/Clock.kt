package net.jbglenn.telltype.core.common

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The only source of time outside tests. Injected so tests control it; nothing calls `System` or
 * `kotlin.time.Clock.System` directly. The session reducer never reads a clock: times reach it inside events.
 */
public interface Clock {
    /** Wall-clock time. Use for timestamps that are stored or shown (history `created_at`, retention cut-off). */
    public fun now(): Instant

    /**
     * Monotonic time since an arbitrary fixed origin (device boot on Android). Never jumps when the user
     * changes the wall clock. Use only for measuring durations, such as stage timings, by subtracting two
     * readings taken in the same process.
     */
    public fun monotonic(): Duration
}
