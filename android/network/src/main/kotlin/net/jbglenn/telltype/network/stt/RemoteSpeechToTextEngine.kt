package net.jbglenn.telltype.network.stt

import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.debug
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioEncoding
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.SpeechEngineFailed
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.domain.port.SpeechToTextEngine
import net.jbglenn.telltype.network.http.GateRoute
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.http.TranscriptionReply
import net.jbglenn.telltype.network.http.exchange
import net.jbglenn.telltype.network.http.gateRequest
import net.jbglenn.telltype.network.http.parseTranscription
import net.jbglenn.telltype.network.http.readGateResponse
import net.jbglenn.telltype.network.route.CallPolicy
import net.jbglenn.telltype.network.route.GateTarget
import net.jbglenn.telltype.network.route.ServerRouter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Speech to text at the server address, `POST /v1/audio/transcriptions` (Appendix C, D60). The recording is
 * streamed from disk as the multipart `file` part (never loaded into memory); `model` is the chosen speech model
 * (`whisper-1` when none is chosen); dictionary terms go in `prompt`; `X-Request-Id` is the session id; a
 * `Server-Timing` header, when the server sends one, becomes the transcript's server timings. Retry and the breaker
 * are the router's ([CallPolicy.TRANSCRIPTION]).
 */
internal class RemoteSpeechToTextEngine @Inject constructor(
    private val router: ServerRouter,
    private val timeouts: NetworkTimeouts,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : SpeechToTextEngine {

    /**
     * A `GET /v1/models` at the speech address ([CallPolicy.WARM_UP]): any answer leaves a warm connection for the
     * upload, so the TLS handshake overlaps speaking. Never fails.
     */
    override suspend fun prepare() {
        val outcome = router.execute(ServerRole.SPEECH, CallPolicy.WARM_UP) { target ->
            exchange(target.client, gateRequest(target, GateRoute.MODELS, null, null), timeouts.health) { response ->
                readGateResponse(response) { _, _ -> Unit }
            }
        }
        logger.debug(TAG) { "warm-up ${if (outcome is Outcome.Ok) "done" else "failed"}" }
    }

    override suspend fun transcribe(audio: AudioClip, hints: SttHints): Outcome<Transcript, SttError> {
        val file = File(audio.path)
        val readable = withContext(dispatchers.io) { file.isFile && file.canRead() }
        if (!readable) {
            logger.warn(TAG, null) { "recording ${audio.recording.value} is not readable; nothing uploaded" }
            return Outcome.Err(SpeechEngineFailed)
        }
        val outcome = router.execute(ServerRole.SPEECH, CallPolicy.TRANSCRIPTION) { target ->
            exchange(target.client, request(target, audio, file, hints), timeouts.transcription) { response ->
                readGateResponse(response) { body, timings -> parseTranscription(body)?.let { Parsed(it, timings) } }
            }
        }
        return when (outcome) {
            is Outcome.Err -> outcome
            is Outcome.Ok -> transcriptOf(audio, outcome.value)
        }
    }

    private fun transcriptOf(audio: AudioClip, parsed: Parsed): Outcome<Transcript, SttError> {
        val text = parsed.reply.text.trim()
        if (text.isEmpty()) {
            logger.info(TAG) { "session ${audio.session.value}: empty transcript" }
            return Outcome.Err(EmptyTranscript)
        }
        val duration = parsed.reply.durationSeconds?.seconds ?: audio.duration
        logger.info(TAG) { "session ${audio.session.value}: transcribed, timings ${parsed.timings}" }
        return Outcome.Ok(Transcript(text, duration, parsed.timings))
    }

    private fun request(target: GateTarget, audio: AudioClip, file: File, hints: SttHints) = gateRequest(
        target = target,
        route = GateRoute.TRANSCRIPTION,
        session = audio.session,
        body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mediaTypeOf(audio.format.encoding)))
            .addFormDataPart("model", target.models.speech ?: ServerModels.DEFAULT_SPEECH)
            .addFormDataPart("language", LANGUAGE)
            .addFormDataPart("response_format", RESPONSE_FORMAT)
            .apply { promptOf(hints)?.let { addFormDataPart("prompt", it) } }
            .build(),
    )

    private class Parsed(val reply: TranscriptionReply, val timings: List<ServerTiming>)

    private companion object {
        val TAG = LogTag("RemoteStt")

        const val LANGUAGE = "en"
        const val RESPONSE_FORMAT = "json"

        /** The gate passes at most this many terms to the recogniser. */
        const val MAX_TERMS = 100

        fun mediaTypeOf(encoding: AudioEncoding) = when (encoding) {
            AudioEncoding.WAV -> "audio/wav"
            AudioEncoding.OGG_OPUS -> "audio/ogg"
        }.toMediaType()

        /** Terms trimmed, without commas (the list separator), de-duplicated, at most [MAX_TERMS]. */
        fun promptOf(hints: SttHints): String? = hints.terms
            .map { it.replace(',', ' ').trim() }
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_TERMS)
            .takeIf(List<String>::isNotEmpty)
            ?.joinToString(", ")
    }
}
