package net.jbglenn.telltype.domain.cleanup

import kotlin.math.ceil
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest

/** Element content: `&` first, then `<` and `>` (`shared/prompts/README.md`, "Escaping rule"). */
internal fun escapeText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Attribute values: [escapeText] plus `"`. */
internal fun escapeAttribute(value: String): String = escapeText(value).replace("\"", "&quot;")

/**
 * Single-pass substitution of `{{name}}`; inserted values are never scanned again. A placeholder without a value
 * stays as it is (parsePromptSet guarantees that every placeholder of a shipped template has one).
 */
internal fun render(template: String, values: Map<String, String>): String =
    PlaceholderPattern.replace(template) { match -> values[match.groupValues[1]] ?: match.value }

/** Terms trimmed, empty ones and exact duplicates dropped (first kept), in order. */
internal fun dictionaryAsSent(terms: List<String>): List<String> =
    terms.map(::pythonStrip).filter { it.isNotEmpty() }.distinct()

/** `clamp(ceil(ratio × ceil(inputCodePoints / charsPerToken)) + margin, min, max)`. */
internal fun maxTokens(rule: MaxTokensRule, input: String): Int {
    val charsPerToken = rule.charsPerToken.coerceAtLeast(1)
    val estimated = (codePointLength(input) + charsPerToken - 1) / charsPerToken
    val cap = ceil(rule.ratio * estimated).toInt() + rule.margin
    return minOf(rule.max, maxOf(rule.min, cap))
}

/** The cleanup request (`shared/prompts/README.md`, "Building the cleanup request"). */
internal fun cleanupPrompt(request: CleanupRequest, prompts: PromptSet): CleanupPrompt {
    val toneLine = prompts.toneLines[request.tone].orEmpty()
    val dictionary = dictionaryAsSent(request.dictionary)
    val before = lastCodePoints(request.contextBefore, prompts.contextBeforeMaxChars)
    val transcript = pythonStrip(request.transcript)
    val user = render(
        prompts.cleanupUserTemplate,
        mapOf(
            "tone" to escapeText(toneLine),
            "dictionary" to escapeText(dictionary.joinToString(", ")),
            "app" to escapeAttribute(prompts.categoryLabels[request.category].orEmpty()),
            "field" to escapeAttribute(request.fieldHint.orEmpty()),
            "before" to escapeText(before),
            "transcript" to escapeText(transcript),
        ),
    )
    val chat = chatPrompt(prompts.cleanupRequest, prompts.cleanupSystem, user, transcript)
    val inputs = GuardInputs(
        transcript = transcript,
        systemPrompt = prompts.cleanupSystem,
        toneLine = toneLine,
        dictionary = dictionary,
        contextBefore = before,
    )
    return CleanupPrompt(chat, inputs)
}

/** The Command Mode request: selection escaped but not trimmed, instruction trimmed and escaped. */
internal fun commandPrompt(request: CommandRequest, prompts: PromptSet): ChatPrompt {
    val instruction = pythonStrip(request.instruction)
    val user = render(
        prompts.commandUserTemplate,
        mapOf("selection" to escapeText(request.selection), "instruction" to escapeText(instruction)),
    )
    return chatPrompt(prompts.commandRequest, prompts.commandSystem, user, request.selection + instruction)
}

private fun chatPrompt(settings: ModelRequestSettings, system: String, user: String, input: String): ChatPrompt =
    ChatPrompt(
        model = settings.model,
        messages = listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, user)),
        temperature = settings.temperature,
        maxTokens = maxTokens(settings.maxTokens, input),
        enableThinking = settings.enableThinking,
    )
