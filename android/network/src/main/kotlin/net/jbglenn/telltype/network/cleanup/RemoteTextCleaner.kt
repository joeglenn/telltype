package net.jbglenn.telltype.network.cleanup

import javax.inject.Inject
import kotlin.time.Duration
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.cleanup.LengthViolation
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.CleanupRejected
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.cleanup.ChatPrompt
import net.jbglenn.telltype.domain.cleanup.ModelReply
import net.jbglenn.telltype.domain.port.TextCleaner
import net.jbglenn.telltype.network.http.GateRoute
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.http.chatRequestJson
import net.jbglenn.telltype.network.http.exchange
import net.jbglenn.telltype.network.http.gateRequest
import net.jbglenn.telltype.network.http.parseChatReply
import net.jbglenn.telltype.network.http.readGateResponse
import net.jbglenn.telltype.network.route.CallPolicy
import net.jbglenn.telltype.network.route.ServerRouter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cleanup and Command Mode at the text model address, `POST /v1/chat/completions` (Appendix C, D60,
 * `shared/prompts/README.md`), with the chosen cleanup or command model as `model` (the names of
 * `shared/prompts/request.json`, our gate's aliases, when none is chosen).
 * The request is built and the reply judged by the domain's functions ([CleanupLogic]); a reply a guard rejects
 * is [CleanupRejected], never text. Not retried ([CallPolicy.CHAT]): cleanup degrades to
 * rule-based text instead (section 10). The caller applies the model deadline.
 *
 * Command Mode has no guard list of its own in `shared/GUARDS.md`; its reply is extracted the same way and
 * rejected when it is empty or was cut off at the token cap, because either would replace the selection with
 * something the speaker did not ask for.
 */
internal class RemoteTextCleaner @Inject constructor(
    private val router: ServerRouter,
    private val assets: CleanupAssets,
    private val logic: CleanupLogic,
    private val timeouts: NetworkTimeouts,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : TextCleaner {

    override suspend fun clean(request: CleanupRequest): Outcome<CleanedText, CleanupError> {
        val prompt = withContext(dispatchers.default) { logic.buildCleanup(request, assets.prompts) }
        val reply = when (val outcome = chat(prompt.chat, request.session, timeouts.cleanup) { it.cleanup }) {
            is Outcome.Err -> return outcome
            is Outcome.Ok -> outcome.value
        }
        val verdict = withContext(dispatchers.default) { logic.guard(reply, prompt.guardInputs, assets.rules) }
        return accepted(request.session, verdict, reply)
    }

    override suspend fun command(request: CommandRequest): Outcome<CleanedText, CleanupError> {
        val chat = withContext(dispatchers.default) { logic.buildCommand(request, assets.prompts) }
        val reply = when (val outcome = chat(chat, request.session, timeouts.command) { it.command }) {
            is Outcome.Err -> return outcome
            is Outcome.Ok -> outcome.value
        }
        val verdict = withContext(dispatchers.default) {
            val text = logic.extract(reply, request.selection, assets.rules.replyExtraction)
            when {
                text.isBlank() -> GuardVerdict.EmptyReply

                reply.finishReason in assets.rules.length.failOnFinishReason -> GuardVerdict.LengthOutOfBounds(
                    violation = LengthViolation.CUT_OFF,
                    replyTokens = text.split(WHITESPACE).count(String::isNotEmpty),
                    bound = null,
                )

                else -> GuardVerdict.Accepted(text)
            }
        }
        return accepted(request.session, verdict, reply)
    }

    private fun accepted(session: SessionId, verdict: GuardVerdict, reply: ModelReply) = when (verdict) {
        is GuardVerdict.Accepted -> {
            logger.info(TAG) { "session ${session.value}: model reply accepted, timings ${reply.serverTimings}" }
            Outcome.Ok(CleanedText(verdict.text, CleanupStatus.APPLIED, snippetOnly = false, reply.serverTimings))
        }

        is GuardVerdict.Rejected -> {
            logger.info(TAG) { "session ${session.value}: model reply rejected by guard ${verdict.guard.id}" }
            Outcome.Err(CleanupRejected(verdict))
        }
    }

    private suspend fun chat(
        prompt: ChatPrompt,
        session: SessionId,
        timeout: Duration,
        chosen: (ServerModels) -> String?,
    ): Outcome<ModelReply, NetworkError> = router.execute(ServerRole.TEXT, CallPolicy.CHAT) { target ->
        val model = chosen(target.models) ?: prompt.model
        logger.info(TAG) { "session ${session.value}: model $model at ${target.endpoint.baseUrl}" }
        val body = chatRequestJson(prompt.copy(model = model)).toRequestBody(JSON)
        exchange(target.client, gateRequest(target, GateRoute.CHAT, session, body), timeout) { response ->
            readGateResponse(response) { text, timings ->
                parseChatReply(text)?.let { ModelReply(it.content, it.finishReason, timings) }
            }
        }
    }

    private companion object {
        val TAG = LogTag("RemoteCleaner")
        val JSON = "application/json; charset=utf-8".toMediaType()
        val WHITESPACE = Regex("\\s+")
    }
}
