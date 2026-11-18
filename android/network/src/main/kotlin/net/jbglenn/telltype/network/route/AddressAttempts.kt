package net.jbglenn.telltype.network.route

import kotlin.time.Duration
import kotlinx.coroutines.delay
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.debug
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.failure.FailureScope
import net.jbglenn.telltype.network.failure.networkErrorFor
import net.jbglenn.telltype.network.failure.scopeOf

/** How one attempt at one address ended, as the router judges it (`ServerRouter`). */
internal sealed interface Tried<out T> {
    /** The attempt produced [value]. */
    data class Succeeded<out T>(val value: T) : Tried<T>

    /**
     * The attempt failed.
     *
     * @property failure what happened.
     * @property error the `NetworkError` it maps to.
     * @property scope whose fault it was, which decides retry and failover.
     */
    data class Failed(val failure: CallFailure, val error: NetworkError, val scope: FailureScope) : Tried<Nothing>
}

/**
 * One attempt at one address, with its circuit breaker and its log line. Thread-safe.
 */
internal class AddressAttempts(private val breakers: CircuitBreakers, private val logger: Logger) {
    /**
     * Runs [attempt] at [target] when [policy]'s breaker rule allows it, after waiting [pause] (a retry's pause,
     * spent only when the address is not skipped); null when the breaker is open, so the address is skipped. A
     * cancelled attempt gives a probe permit back.
     */
    suspend fun <T> once(
        policy: CallPolicy,
        target: GateTarget,
        pause: Duration,
        attempt: suspend (GateTarget) -> Attempt<T>,
    ): Tried<T>? {
        val endpoint = target.endpoint
        val permit = if (policy.useBreaker) breakers.acquire(endpoint) else CircuitBreakers.Permit.NORMAL
        if (permit == null) {
            logger.debug(TAG) { "${policy.name}: skipping ${endpoint.baseUrl}, breaker open" }
            return null
        }
        return try {
            delay(pause)
            judge(policy, endpoint, attempt(target))
        } finally {
            breakers.release(endpoint, permit)
        }
    }

    private fun <T> judge(policy: CallPolicy, endpoint: ServerEndpoint, result: Attempt<T>): Tried<T> = when (result) {
        is Attempt.Success -> {
            breakers.recordSuccess(endpoint)
            Tried.Succeeded(result.value)
        }

        is Attempt.Failure -> {
            val failure = result.failure
            val failed = Tried.Failed(failure, networkErrorFor(failure), scopeOf(failure))
            if (failed.scope ==
                FailureScope.ADDRESS
            ) {
                breakers.recordFailure(endpoint)
            } else {
                breakers.recordSuccess(endpoint)
            }
            log(policy, endpoint, failed)
            failed
        }
    }

    private fun log(policy: CallPolicy, endpoint: ServerEndpoint, failed: Tried.Failed) {
        val detail = when (val failure = failed.failure) {
            is CallFailure.Http -> "HTTP ${failure.status} ${failure.serverCode ?: "-"}"
            is CallFailure.Transport -> failure.kind.name + if (failure.connected) "" else " before connecting"
            CallFailure.MalformedBody -> "malformed body"
        }
        val line = "${policy.name}: ${endpoint.baseUrl} failed: $detail (${failed.error.code})"
        if (failed.scope == FailureScope.ADDRESS) logger.warn(TAG, null) { line } else logger.info(TAG) { line }
    }

    private companion object {
        val TAG = LogTag("ServerRouter")
    }
}
