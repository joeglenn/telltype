package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/**
 * The shared prompt set, `shared/prompts/` (docs/PLAN.md decision D11), parsed. The texts and numbers are never
 * written in Kotlin: they are loaded from the bundled files with `parsePromptSet`. Message texts are the file
 * contents with exactly one trailing newline removed (`shared/prompts/README.md`).
 *
 * @property version the `VERSION` file.
 * @property cleanupSystem `cleanup_system.txt`.
 * @property cleanupUserTemplate `cleanup_user.tmpl` (placeholders `{{tone}}`, `{{dictionary}}`, `{{app}}`,
 *   `{{field}}`, `{{before}}`, `{{transcript}}`).
 * @property commandSystem `command_system.txt`.
 * @property commandUserTemplate `command_user.tmpl` (placeholders `{{selection}}`, `{{instruction}}`).
 * @property toneLines `tones.json`: a line for every [Tone].
 * @property categoryLabels `categories.json`: a prompt label for every [AppCategory].
 * @property cleanupRequest `request.json` → `cleanup`.
 * @property commandRequest `request.json` → `command`.
 * @property contextBeforeMaxChars `request.json` → `cleanup.context_before_max_chars`: how many code points of
 *   text before the cursor go into the prompt.
 */
public data class PromptSet(
    public val version: Int,
    public val cleanupSystem: String,
    public val cleanupUserTemplate: String,
    public val commandSystem: String,
    public val commandUserTemplate: String,
    public val toneLines: Map<Tone, String>,
    public val categoryLabels: Map<AppCategory, String>,
    public val cleanupRequest: ModelRequestSettings,
    public val commandRequest: ModelRequestSettings,
    public val contextBeforeMaxChars: Int,
)

/**
 * The request settings for one model role (`request.json`). `stream` is always false in version 1 and is not
 * represented.
 *
 * @property model the `model` field sent to the gate (`cleanup` or `assistant`).
 * @property temperature sampling temperature.
 * @property enableThinking the `chat_template_kwargs.enable_thinking` value (false).
 * @property maxTokens the output cap rule.
 */
public data class ModelRequestSettings(
    public val model: String,
    public val temperature: Double,
    public val enableThinking: Boolean,
    public val maxTokens: MaxTokensRule,
)

/**
 * `max_tokens = clamp(ceil(ratio × ceil(inputCodePoints / charsPerToken)) + margin, min, max)`.
 *
 * @property charsPerToken assumed characters per input token; `> 0`.
 * @property ratio output tokens allowed per input token.
 * @property margin tokens added after scaling.
 * @property min lower clamp.
 * @property max upper clamp; `>= min`.
 */
public data class MaxTokensRule(
    public val charsPerToken: Int,
    public val ratio: Double,
    public val margin: Int,
    public val min: Int,
    public val max: Int,
)

/**
 * The role of a chat message.
 *
 * @property id the `role` value in the request body.
 */
public enum class ChatRole(public val id: String) {
    /** The system message. */
    SYSTEM("system"),

    /** The user message. */
    USER("user"),
}

/**
 * One chat message.
 *
 * @property role who speaks.
 * @property content the text (contains dictated text and context: never logged).
 */
public data class ChatMessage(public val role: ChatRole, public val content: String) {
    override fun toString(): String = "ChatMessage(role=$role, content=‹${content.length} chars›)"
}

/**
 * A complete `POST /v1/chat/completions` body (OpenAI format, `stream: false`), as built by the domain and sent
 * unchanged by `:network`.
 *
 * @property model the `model` field.
 * @property messages system message, then user message.
 * @property temperature sampling temperature.
 * @property maxTokens output cap.
 * @property enableThinking `chat_template_kwargs.enable_thinking`.
 */
public data class ChatPrompt(
    public val model: String,
    public val messages: List<ChatMessage>,
    public val temperature: Double,
    public val maxTokens: Int,
    public val enableThinking: Boolean,
)

/**
 * What the chat endpoint returned, before extraction.
 *
 * @property content `choices[0].message.content`, null when absent or JSON null.
 * @property finishReason `choices[0].finish_reason`, null when absent.
 * @property serverTimings the reply's `Server-Timing` entries.
 */
public data class ModelReply(
    public val content: String?,
    public val finishReason: String?,
    public val serverTimings: List<ServerTiming>,
) {
    override fun toString(): String =
        "ModelReply(content=${content?.let { "‹${it.length} chars›" }}, finishReason=$finishReason)"
}

/**
 * Exactly what was sent in a cleanup prompt, as the guards need it (`shared/GUARDS.md` section 1).
 *
 * @property transcript the transcript as sent in `<transcript>`, before escaping, markers included.
 * @property systemPrompt the system message as sent.
 * @property toneLine the tone line as sent.
 * @property dictionary the dictionary terms as sent (trimmed, de-duplicated).
 * @property contextBefore the text before the cursor as sent (after the code-point cut).
 */
public data class GuardInputs(
    public val transcript: String,
    public val systemPrompt: String,
    public val toneLine: String,
    public val dictionary: List<String>,
    public val contextBefore: String,
) {
    override fun toString(): String = "GuardInputs(transcript=‹${transcript.length} chars›, " +
        "dictionary=${dictionary.size} terms, contextBefore=‹${contextBefore.length} chars›)"
}

/**
 * A built cleanup request.
 *
 * @property chat the body to send.
 * @property guardInputs what the guards compare the reply with.
 */
public data class CleanupPrompt(public val chat: ChatPrompt, public val guardInputs: GuardInputs)

/**
 * A bundled shared file is missing or does not have the documented structure. A build defect, caught by tests;
 * at run time the app cannot clean without its prompts and degrades to rule-based cleanup.
 *
 * @property file the file name inside `shared/` (for example `prompts/tones.json`).
 * @property problem what is wrong, in words.
 */
public data class AssetError(public val file: String, public val problem: String)
