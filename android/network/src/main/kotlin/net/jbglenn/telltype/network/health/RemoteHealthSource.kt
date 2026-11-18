package net.jbglenn.telltype.network.health

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.OfferedModels
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.network.check.AddressProbes
import net.jbglenn.telltype.network.check.textModels
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.route.Attempt
import net.jbglenn.telltype.network.route.CallPolicy
import net.jbglenn.telltype.network.route.GateTarget
import net.jbglenn.telltype.network.route.ServerRouter

/**
 * The server's state (D60). [probe] asks the server address `GET /v1/models` and our gate's `/healthz` (kept only
 * when it answers in the gate's format), then the separate text model address, if one is set, `GET /v1/models`.
 * Every address must answer; a 404 for the list still proves an address answers (some speech servers have none).
 * A 503 from our gate's health (speech not ready) is `Unreachable(ServerWarmingUp)`. Breakers ignored, no retry.
 */
@Singleton
internal class RemoteHealthSource @Inject constructor(
    private val settings: SettingsRepository,
    private val router: ServerRouter,
    private val probes: AddressProbes,
    private val clock: Clock,
    private val logger: Logger,
) : ServerHealthSource {
    private val state = MutableStateFlow<ServerReachability>(ServerReachability.Unknown)

    override val reachability: StateFlow<ServerReachability> = state.asStateFlow()

    override suspend fun probe(): ServerReachability {
        val started = clock.monotonic()
        val server = settings.current().server
        val speech = router.execute(ServerRole.SPEECH, CallPolicy.HEALTH) { speechView(it) }
        val text = if (server.textAddress == null) {
            null
        } else {
            router.execute(ServerRole.TEXT, CallPolicy.HEALTH) { probes.models(it) }
        }
        val address = server.address
        val reachability = when {
            address == null -> ServerReachability.Unreachable(ServerNotConfigured, clock.now())

            speech is Outcome.Err -> ServerReachability.Unreachable(speech.error, clock.now())

            text is Outcome.Err -> ServerReachability.Unreachable(text.error, clock.now())

            else -> {
                val view = (speech as Outcome.Ok).value
                val textModels = (text as? Outcome.Ok)?.value?.models
                ServerReachability.Reachable(
                    endpoint = address,
                    health = view.health,
                    roundTrip = clock.monotonic() - started,
                    checkedAt = clock.now(),
                    textEndpoint = server.textAddress,
                    offered = OfferedModels(
                        speech = view.models,
                        text = if (server.textAddress == null) view.textModels() else textModels,
                    ),
                )
            }
        }
        logger.info(TAG) { "health: ${describe(reachability)}" }
        state.value = reachability
        return reachability
    }

    private suspend fun speechView(target: GateTarget): Attempt<SpeechView> {
        val listed = probes.models(target)
        if (listed is Attempt.Failure) return listed
        val health = probes.gateHealth(target)
        val warmingUp = (health as? Attempt.Failure)?.failure?.takeIf { it is CallFailure.Http && it.status == BUSY }
        return if (warmingUp != null) {
            Attempt.Failure(warmingUp)
        } else {
            Attempt.Success(SpeechView((listed as Attempt.Success).value.models, (health as? Attempt.Success)?.value))
        }
    }

    /** What the server address answered: its list and our gate's health, each null when absent. */
    private class SpeechView(val models: List<OfferedModel>?, val health: ServerHealth?) {
        /** The text models: the list, or the models our gate's health names when only its health answered. */
        fun textModels(): List<OfferedModel>? = models ?: health?.textModels()
    }

    private companion object {
        val TAG = LogTag("ServerHealth")
        const val BUSY = 503

        fun describe(reachability: ServerReachability): String = when (reachability) {
            ServerReachability.Unknown -> "unknown"

            is ServerReachability.Reachable -> buildString {
                append("reachable via ${reachability.endpoint.baseUrl}")
                reachability.textEndpoint?.let { append(", text via ${it.baseUrl}") }
                append(reachability.health?.let { ", gate ${it.status.id}" } ?: ", no gate health")
                append(", models ${reachability.offered.speech?.size ?: "-"}/${reachability.offered.text?.size ?: "-"}")
                append(" in ${reachability.roundTrip}")
            }

            is ServerReachability.Unreachable -> "unreachable (${reachability.error.code})"
        }
    }
}
