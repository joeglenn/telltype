package net.jbglenn.telltype.network.http

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.jbglenn.telltype.core.model.settings.BackendHealth
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.domain.cleanup.ChatPrompt

/*
 * The JSON bodies of Appendix C, read with the tree API (no generated serializers). Every parser is total: a body
 * that is not the documented shape gives null, never an exception.
 */

/**
 * The transcription reply `{ "text": "…", "duration": 9.8 }`.
 *
 * @property text the transcript as sent (may be blank; the engine turns that into `EmptyTranscript`).
 * @property durationSeconds the audio duration, null when the server did not send one.
 */
internal class TranscriptionReply(val text: String, val durationSeconds: Double?) {
    override fun toString(): String = "TranscriptionReply(text=‹${text.length} chars›, duration=$durationSeconds)"
}

/**
 * The parts of an OpenAI-format chat reply the guards need.
 *
 * @property content `choices[0].message.content`, null when absent or JSON null.
 * @property finishReason `choices[0].finish_reason`, null when absent.
 */
internal class ChatReply(val content: String?, val finishReason: String?) {
    override fun toString(): String = "ChatReply(content=${content?.let { "‹${it.length} chars›" }}, " +
        "finishReason=$finishReason)"
}

/** Parses a transcription reply. */
internal fun parseTranscription(body: String): TranscriptionReply? {
    val root = parseObject(body)
    val duration = (root?.get("duration") as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        ?.takeIf { it.isFinite() && it >= 0.0 }
    return root?.string("text")?.let { TranscriptionReply(it, duration) }
}

/** Parses a chat completion reply. */
internal fun parseChatReply(body: String): ChatReply? {
    val choice = (parseObject(body)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    val content = message?.get("content")
    val text = (content as? JsonPrimitive)?.takeIf { it.isString }?.content
    val readable = message != null && (content == null || content == JsonNull || text != null)
    return if (readable) ChatReply(text, choice.string("finish_reason")) else null
}

/** Parses the `/healthz` body. */
internal fun parseHealth(body: String): ServerHealth? {
    val root = parseObject(body)
    val status = root?.string("status")?.let { id -> ServerStatus.entries.firstOrNull { it.id == id } }
    val version = root?.string("version")
    val backends = listOf("stt", "cleanup", "assistant").map { backend(root?.get(it)) }
    if (status == null || version == null || backends.any { it == null }) return null
    val (stt, cleanup, assistant) = backends.filterNotNull()
    return ServerHealth(status, version, stt, cleanup, assistant)
}

/** The `error.code` of an error body, or null. */
internal fun parseErrorCode(body: String): String? = (parseObject(body)?.get("error") as? JsonObject)?.string("code")

/**
 * The chat request body for [prompt], exactly the fields of `shared/prompts/request.json`: `model`, `messages`,
 * `stream` (false), `temperature`, `max_tokens`, `chat_template_kwargs.enable_thinking`.
 */
internal fun chatRequestJson(prompt: ChatPrompt): String = buildJsonObject {
    put("model", prompt.model)
    putJsonArray("messages") {
        for (message in prompt.messages) {
            add(
                buildJsonObject {
                    put("role", message.role.id)
                    put("content", message.content)
                },
            )
        }
    }
    put("stream", false)
    put("temperature", prompt.temperature)
    put("max_tokens", prompt.maxTokens)
    putJsonObject("chat_template_kwargs") { put("enable_thinking", prompt.enableThinking) }
}.toString()

private fun backend(element: JsonElement?): BackendHealth? {
    val entry = element as? JsonObject
    val ready = (entry?.get("ready") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val model = entry?.string("model")
    return if (ready == null || model == null) null else BackendHealth(ready, model, entry.string("engine"))
}

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun parseObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (_: SerializationException) {
    null
}
