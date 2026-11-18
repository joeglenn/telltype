package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.common.Outcome

/** request.json, parsed. */
internal class RequestFile(
    val cleanup: ModelRequestSettings,
    val command: ModelRequestSettings,
    val contextBeforeMaxChars: Int,
)

/** Stands in for request.json after an error; never part of a returned [PromptSet]. */
internal val NoRequest = ModelRequestSettings("", 0.0, false, MaxTokensRule(1, 0.0, 0, 0, 0)).let {
    RequestFile(it, it, 0)
}

/** Parses `request.json`. */
internal fun decodeRequest(files: Map<String, String>): Outcome<RequestFile, AssetError> {
    val (decoder, root) = jsonFile(files, "request.json")
    decoder.members(root, setOf("version", "cleanup", "command"))
    decoder.int(root["version"])
    val cleanup = root["cleanup"]
    decoder.members(cleanup, RoleKeys + "context_before_max_chars")
    decoder.members(root["command"], RoleKeys)
    val contextChars = decoder.int(cleanup["context_before_max_chars"])
    decoder.expect(contextChars >= 0) { "cleanup.context_before_max_chars must not be negative" }
    val request = RequestFile(
        cleanup = decodeRole(decoder, cleanup),
        command = decodeRole(decoder, root["command"]),
        contextBeforeMaxChars = contextChars,
    )
    return decoder.finish(request)
}

private val RoleKeys = setOf("model", "stream", "temperature", "chat_template_kwargs", "max_tokens")

private fun decodeRole(decoder: AssetDecoder, node: JsonNode): ModelRequestSettings {
    decoder.expect(!decoder.boolean(node["stream"])) { "${node.path}.stream must be false" }
    val kwargs = node["chat_template_kwargs"]
    decoder.members(kwargs, setOf("enable_thinking"))
    val rule = node["max_tokens"]
    decoder.members(rule, setOf("chars_per_token", "ratio", "margin", "min", "max"))
    val maxTokens = MaxTokensRule(
        charsPerToken = decoder.int(rule["chars_per_token"]),
        ratio = decoder.double(rule["ratio"]),
        margin = decoder.int(rule["margin"]),
        min = decoder.int(rule["min"]),
        max = decoder.int(rule["max"]),
    )
    decoder.expect(maxTokens.charsPerToken > 0 && maxTokens.max >= maxTokens.min) {
        "${rule.path} needs chars_per_token > 0 and max >= min"
    }
    return ModelRequestSettings(
        model = decoder.string(node["model"]),
        temperature = decoder.double(node["temperature"]),
        enableThinking = decoder.boolean(kwargs["enable_thinking"]),
        maxTokens = maxTokens,
    )
}
