package net.jbglenn.telltype.network.route

import kotlin.time.Duration
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.network.failure.isTransient
import net.jbglenn.telltype.network.http.GateClients

/**
 * Sends every call to the one address of its role (D60: the server address for speech, the text model address
 * for text, the same one unless a second is set) and applies the retry and circuit-breaker rules of
 * docs/PLAN.md section 10:
 *
 * - A transient failure (no answer, 502, 503, 504) is retried once when the policy allows, after a jittered
 *   pause (503: its `Retry-After`) and with the patient connect timeout. A certificate problem or a refused
 *   local-network permission is not retried, because it would fail the same way. Other 4xx and malformed bodies
 *   are final at once.
 * - A breaker per address skips it after three consecutive address failures for 30 seconds, then allows one probe.
 * - Plain HTTP to an address that is not private is refused before anything is sent (`ServerNotConfigured`).
 *
 * Every pause is a coroutine `delay` on the caller's dispatcher, so tests run on virtual time.
 */
internal class ServerRouter(
    private val settings: SettingsRepository,
    private val clients: GateClients,
    private val breakers: CircuitBreakers,
    private val retryPolicy: RetryPolicy,
    private val logger: Logger,
) {
    private val attempts = AddressAttempts(breakers, logger)

    /** Runs [attempt] at the stored address for [role] as [policy] says. */
    suspend fun <T> execute(
        role: ServerRole,
        policy: CallPolicy,
        attempt: suspend (GateTarget) -> Attempt<T>,
    ): Outcome<T, NetworkError> {
        val server = settings.current().server
        val endpoint = server.endpointFor(role) ?: return Outcome.Err(ServerNotConfigured)
        return executeAt(Destination(endpoint, server.key, server.certificateFor(role), server.models), policy, attempt)
    }

    /** Runs [attempt] at [destination] (Setup's candidate, not yet stored) as [policy] says. */
    suspend fun <T> executeAt(
        destination: Destination,
        policy: CallPolicy,
        attempt: suspend (GateTarget) -> Attempt<T>,
    ): Outcome<T, NetworkError> {
        val endpoint = destination.endpoint
        if (endpoint.isPlainHttp && !endpoint.isPrivate) {
            logger.warn(TAG, null) { "${policy.name}: plain HTTP to a public address refused (${endpoint.host})" }
            return Outcome.Err(ServerNotConfigured)
        }
        val routes = clients.forEndpoint(endpoint, destination.certificate)
        fun target(retry: Boolean) =
            GateTarget(endpoint, destination.key, if (retry) routes.retry else routes.firstTry, destination.models)
        val first = attempts.once(policy, target(retry = false), Duration.ZERO, attempt)
        val retryAfter = (first as? Tried.Failed)?.failure?.takeIf { policy.retry && isTransient(it) }
        val last = if (retryAfter == null) {
            first
        } else {
            attempts.once(policy, target(retry = true), retryPolicy.delayBefore(retryAfter), attempt) ?: first
        }
        return last?.outcome() ?: Outcome.Err(ServerUnreachable)
    }

    private fun <T> Tried<T>.outcome(): Outcome<T, NetworkError> = when (this) {
        is Tried.Succeeded -> Outcome.Ok(value)
        is Tried.Failed -> Outcome.Err(error)
    }

    /**
     * One address and how to talk to it.
     *
     * @property endpoint the address.
     * @property key the bearer key, null for none.
     * @property certificate the pinned certificate, null for the system's trust (or HTTP).
     * @property models the model names to send.
     */
    class Destination(
        val endpoint: ServerEndpoint,
        val key: ServerKey?,
        val certificate: CertificateFingerprint?,
        val models: ServerModels = ServerModels.Unset,
    ) {
        override fun toString(): String = "Destination(${endpoint.baseUrl}, pinned=${certificate != null})"
    }

    private companion object {
        val TAG = LogTag("ServerRouter")
    }
}
