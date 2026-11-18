package net.jbglenn.telltype.domain.coordinator

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn

/**
 * The one place where the coordinator calls a port: every call has a timeout, and an exception a port throws
 * (a broken contract: ports return `Outcome`) is turned into the caller's failure value instead of escaping. Only
 * the exception's class is logged, never its message, which could hold dictated text.
 */
internal class PortCalls(private val logger: Logger) {
    /**
     * Runs [block] within [timeout]; [onTimeout] when it runs out, [onError] when the port throws. The caller's own
     * cancellation propagates; a cancellation that does not come from the caller (a port's timeout leaking out)
     * counts as an error.
     */
    suspend fun <T : Any> call(
        name: String,
        timeout: Duration,
        onTimeout: T,
        onError: T = onTimeout,
        block: suspend () -> T,
    ): T = when (val result = attemptOnce(name, timeout, block)) {
        is Attempt.Done -> result.value
        Attempt.TimedOut -> onTimeout
        Attempt.Failed -> onError
    }

    /** Like [call], with null for both a timeout and an error. */
    suspend fun <T : Any> attempt(name: String, timeout: Duration, block: suspend () -> T): T? =
        when (val result = attemptOnce(name, timeout, block)) {
            is Attempt.Done -> result.value
            Attempt.TimedOut, Attempt.Failed -> null
        }

    /** Runs a non-suspending port call (haptics) and logs instead of throwing. */
    // A port may throw anything; the coordinator must survive it and report it (wave 3 rule: no exception escapes).
    @Suppress("TooGenericExceptionCaught")
    fun quietly(name: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            reportThrown(name, failure)
        }
    }

    // A port may throw anything; the coordinator must survive it and report it (wave 3 rule: no exception escapes).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T : Any> attemptOnce(name: String, timeout: Duration, block: suspend () -> T): Attempt<T> =
        try {
            val value = withTimeoutOrNull(timeout) { block() }
            if (value == null) {
                logger.warn(CoordinatorTag, null) { "$name timed out after $timeout" }
                Attempt.TimedOut
            } else {
                Attempt.Done(value)
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            reportThrown(name, cancelled)
            Attempt.Failed
        } catch (failure: Exception) {
            reportThrown(name, failure)
            Attempt.Failed
        }

    private fun reportThrown(name: String, failure: Exception) {
        logger.warn(CoordinatorTag, null) { "$name threw ${failure::class.simpleName}" }
    }

    private sealed interface Attempt<out T> {
        data class Done<out T>(val value: T) : Attempt<T>

        data object TimedOut : Attempt<Nothing>

        data object Failed : Attempt<Nothing>
    }
}
