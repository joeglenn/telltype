package net.jbglenn.telltype.network.route

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.network.failure.CallFailure
import okhttp3.OkHttpClient

/** The result of one attempt at one address. */
internal sealed interface Attempt<out T> {
    /** The attempt produced [value]. */
    data class Success<out T>(val value: T) : Attempt<T>

    /** The attempt failed with [failure]. */
    data class Failure(val failure: CallFailure) : Attempt<Nothing>
}

/**
 * Where and how one attempt is made: chosen by [ServerRouter], used by the call.
 *
 * @property endpoint the address.
 * @property key the bearer key, null when the server needs none.
 * @property client the client for this kind of address (pinned, system trust or plain HTTP).
 * @property models the model names to send.
 */
internal class GateTarget(
    val endpoint: ServerEndpoint,
    val key: ServerKey?,
    val client: OkHttpClient,
    val models: ServerModels = ServerModels.Unset,
) {
    override fun toString(): String = "GateTarget(${endpoint.baseUrl})"
}

/**
 * How the router treats one kind of call (docs/PLAN.md section 10). Each role has one address (D60), so there is
 * no failover.
 *
 * @property retry try the address once more after a transient failure (safe requests only).
 * @property useBreaker skip an address whose breaker is open; an explicit health probe ignores it.
 */
internal enum class CallPolicy(val retry: Boolean, val useBreaker: Boolean) {
    /** Upload and transcription: safe to repeat, so one retry. */
    TRANSCRIPTION(retry = true, useBreaker = true),

    /** Cleanup and Command Mode: never retried; cleanup degrades instead (section 10). */
    CHAT(retry = false, useBreaker = true),

    /** Explicit health probe, Setup's check and the model lists: no retry, breakers not consulted. */
    HEALTH(retry = false, useBreaker = false),

    /** Connection warm-up before an upload: like a probe but respecting breakers. */
    WARM_UP(retry = false, useBreaker = true),
}

/**
 * The pause before the one retry: the server's `Retry-After` (capped at [maxRetryAfter]) when it sent one,
 * otherwise [baseDelay] plus up to [jitter] chosen by [random], so phones do not retry in step.
 */
internal class RetryPolicy(
    private val random: Random,
    private val baseDelay: Duration = 250.milliseconds,
    private val jitter: Duration = 500.milliseconds,
    private val maxRetryAfter: Duration = 5.seconds,
) {
    /** How long to wait before retrying after [failure]. */
    fun delayBefore(failure: CallFailure): Duration {
        val retryAfter = (failure as? CallFailure.Http)?.retryAfter
        if (retryAfter != null) return retryAfter.coerceIn(Duration.ZERO, maxRetryAfter)
        return baseDelay + jitter * random.nextDouble()
    }
}
