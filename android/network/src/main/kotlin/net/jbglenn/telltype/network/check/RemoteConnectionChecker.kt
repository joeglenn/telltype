package net.jbglenn.telltype.network.check

import javax.inject.Inject
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.ServerConnectionChecker
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.route.Attempt
import net.jbglenn.telltype.network.route.CallPolicy
import net.jbglenn.telltype.network.route.GateTarget
import net.jbglenn.telltype.network.route.ServerRouter

/**
 * Setup's check of one address (D60). In order: `GET /v1/models` (a list proves every role); our gate's `/healthz`
 * (read whenever it answers, for the richer health view, and proof of every role when there is no list); for
 * speech without either, a one-second silent transcription. Text without a list or our health fails with
 * `RequestRejected(404, "models_missing")`. Logs which check passed for which role.
 */
internal class RemoteConnectionChecker @Inject constructor(
    private val router: ServerRouter,
    private val probes: AddressProbes,
    private val logger: Logger,
) : ServerConnectionChecker {

    override suspend fun check(candidate: ServerCandidate): Outcome<ServerCheck, NetworkError> {
        val destination = ServerRouter.Destination(candidate.endpoint, candidate.key, candidate.certificate)
        val outcome = router.executeAt(destination, CallPolicy.HEALTH) { target -> checkAt(target, candidate.roles) }
        val described = when (outcome) {
            is Outcome.Ok -> outcome.value.methods.entries.joinToString { (role, way) -> "${role.name} by ${way.id}" } +
                ", ${outcome.value.models?.size ?: "no"} models listed"

            is Outcome.Err -> "failed (${outcome.error.code})"
        }
        logger.info(TAG) { "check ${candidate.endpoint.baseUrl}: $described" }
        return outcome
    }

    private suspend fun checkAt(target: GateTarget, roles: Set<ServerRole>): Attempt<ServerCheck> {
        val listed = when (val models = probes.models(target)) {
            is Attempt.Failure -> return models
            is Attempt.Success -> models.value.models
        }
        val health = (probes.gateHealth(target) as? Attempt.Success)?.value
        val methods = roles.associateWith { method(listed != null, health) }
        val missing = methods.filterValues { it == null }.keys
        val proved = methods.mapNotNull { (role, method) -> method?.let { role to it } }.toMap()
        return when {
            ServerRole.TEXT in missing -> Attempt.Failure(CallFailure.Http(NOT_FOUND, MODELS_MISSING, null))

            ServerRole.SPEECH in missing -> when (val silent = probes.silentTranscription(target)) {
                is Attempt.Failure -> silent

                is Attempt.Success -> Attempt.Success(
                    ServerCheck(target.endpoint, proved + SILENT_SPEECH, listed, health),
                )
            }

            else -> Attempt.Success(ServerCheck(target.endpoint, proved, listed ?: health?.textModels(), health))
        }
    }

    private fun method(listed: Boolean, health: ServerHealth?): ServerCheckMethod? = when {
        listed -> ServerCheckMethod.MODELS
        health != null -> ServerCheckMethod.GATE_HEALTH
        else -> null
    }

    private companion object {
        val TAG = LogTag("ServerCheck")
        const val NOT_FOUND = 404

        /** The code of a text address without `/v1/models`. */
        const val MODELS_MISSING = "models_missing"

        val SILENT_SPEECH = ServerRole.SPEECH to ServerCheckMethod.SILENT_TRANSCRIPTION
    }
}
