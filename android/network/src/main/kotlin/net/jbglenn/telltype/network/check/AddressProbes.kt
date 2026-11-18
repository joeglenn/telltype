package net.jbglenn.telltype.network.check

import javax.inject.Inject
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.network.failure.isSuccess
import net.jbglenn.telltype.network.http.GateRoute
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.http.exchange
import net.jbglenn.telltype.network.http.gateRequest
import net.jbglenn.telltype.network.http.parseHealth
import net.jbglenn.telltype.network.http.parseModelList
import net.jbglenn.telltype.network.http.parseTranscription
import net.jbglenn.telltype.network.http.readGateResponse
import net.jbglenn.telltype.network.route.Attempt
import net.jbglenn.telltype.network.route.GateTarget
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The single requests that tell whether an address answers and what it offers (D60): the model list, our gate's
 * health and a silent transcription. Each is one attempt; the router adds the breaker and the address rules.
 */
internal class AddressProbes @Inject constructor(private val timeouts: NetworkTimeouts) {
    /**
     * `GET /v1/models`. Success with the list, or with null when the address has none (404, 405, 501, or a 2xx
     * body that is not a list); a refused key or a server error fails.
     */
    suspend fun models(target: GateTarget): Attempt<Listed> {
        val request = gateRequest(target, GateRoute.MODELS, null, null)
        return exchange(target.client, request, timeouts.health) { response ->
            when {
                isSuccess(response.code) -> readGateResponse(response) { body, _ -> Listed(parseModelList(body)) }
                response.code in NOT_LISTED -> Attempt.Success(Listed(null))
                else -> readGateResponse(response) { _, _ -> null }
            }
        }
    }

    /** `GET /healthz` in our gate's format; any other answer fails. */
    suspend fun gateHealth(target: GateTarget): Attempt<ServerHealth> {
        val request = gateRequest(target, GateRoute.HEALTH, null, null)
        return exchange(target.client, request, timeouts.health) { response ->
            readGateResponse(response) { body, _ -> parseHealth(body) }
        }
    }

    /**
     * A one-second silent 16 kHz WAV to `POST /v1/audio/transcriptions`: passes when the server answers with a
     * transcription body (its text is usually empty).
     */
    suspend fun silentTranscription(target: GateTarget): Attempt<Unit> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "silence.wav", SilentWav.bytes().toRequestBody(WAV))
            .addFormDataPart("model", target.models.speech ?: ServerModels.DEFAULT_SPEECH)
            .addFormDataPart("response_format", "json")
            .build()
        val request = gateRequest(target, GateRoute.TRANSCRIPTION, null, body)
        return exchange(target.client, request, timeouts.health) { response ->
            readGateResponse(response) { text, _ -> parseTranscription(text)?.let { } }
        }
    }

    /**
     * The answer of [models].
     *
     * @property models the list, null when the address has none.
     */
    class Listed(val models: List<OfferedModel>?)

    private companion object {
        val NOT_LISTED = setOf(404, 405, 501)
        val WAV = "audio/wav".toMediaType()
    }
}

/**
 * The text models our gate's `/healthz` names (cleanup and commands, by their real names), offered when it answers
 * `/healthz` but lists no models; empty names are left out.
 */
internal fun ServerHealth.textModels(): List<OfferedModel> =
    listOf(cleanup.model, assistant.model).filter(String::isNotBlank).distinct().map(::OfferedModel)
