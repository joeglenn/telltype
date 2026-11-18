package net.jbglenn.telltype.network.route

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.model.settings.ServerEndpoint

/**
 * One circuit breaker per address (docs/PLAN.md sections 6.3 and 10): after [threshold] consecutive address
 * failures the address is skipped for [cooldown], then exactly one call may probe it. A probe that succeeds
 * closes the breaker; one that fails opens it for another [cooldown]. Time comes from [Clock.monotonic], so tests
 * move it by hand. Thread-safe.
 *
 * "Success" means the address answered at all: an HTTP error status still proves the address works.
 */
internal class CircuitBreakers(
    private val clock: Clock,
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val cooldown: Duration = DEFAULT_COOLDOWN,
) {
    private sealed interface State {
        data class Closed(val failures: Int) : State

        data class Open(val since: Duration) : State

        data object Probing : State
    }

    private val lock = Any()
    private val states = mutableMapOf<ServerEndpoint, State>()

    /** What [acquire] granted. */
    enum class Permit {
        /** The breaker is closed: call normally. */
        NORMAL,

        /** The cooldown has passed: this call is the single probe. */
        PROBE,
    }

    /** Asks to call [endpoint]: a permit, or null when the address must be skipped now. */
    fun acquire(endpoint: ServerEndpoint): Permit? = synchronized(lock) {
        when (val state = states[endpoint] ?: CLOSED) {
            is State.Closed -> Permit.NORMAL

            State.Probing -> null

            is State.Open -> if (clock.monotonic() - state.since >= cooldown) {
                states[endpoint] = State.Probing
                Permit.PROBE
            } else {
                null
            }
        }
    }

    /** The address answered. */
    fun recordSuccess(endpoint: ServerEndpoint) {
        synchronized(lock) { states[endpoint] = CLOSED }
    }

    /** The address could not be used. */
    fun recordFailure(endpoint: ServerEndpoint) {
        synchronized(lock) {
            val failures = when (val state = states[endpoint] ?: CLOSED) {
                is State.Closed -> state.failures + 1
                State.Probing, is State.Open -> threshold
            }
            states[endpoint] = if (failures >= threshold) State.Open(clock.monotonic()) else State.Closed(failures)
        }
    }

    /**
     * A call holding [permit] ended without a verdict (it was cancelled). A probe gives its turn back, so the
     * next call may probe at once.
     */
    fun release(endpoint: ServerEndpoint, permit: Permit) {
        if (permit != Permit.PROBE) return
        synchronized(lock) {
            if (states[endpoint] == State.Probing) states[endpoint] = State.Open(clock.monotonic() - cooldown)
        }
    }

    /** Whether [endpoint] is skipped right now (for logs and tests). */
    fun isOpen(endpoint: ServerEndpoint): Boolean = synchronized(lock) {
        val state = states[endpoint]
        state != null && state !is State.Closed
    }

    /**
     * Closes every breaker: what they learnt belongs to the network the device has just left. A probe still in
     * flight then reports into a closed breaker, which is harmless.
     */
    fun reset() {
        synchronized(lock) { states.clear() }
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 3
        val DEFAULT_COOLDOWN = 30.seconds
        val CLOSED: State = State.Closed(failures = 0)
    }
}
