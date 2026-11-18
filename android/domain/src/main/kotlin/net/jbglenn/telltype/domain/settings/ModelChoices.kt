package net.jbglenn.telltype.domain.settings

import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerModels

/**
 * Which models carry "(Recommended)", which are chosen by default, and how the deprecated gate aliases are
 * replaced (D58, D60, brief M1).
 *
 * **Command Mode (D58).** A model is recommended when its id or its model file name (llama.cpp's `path`),
 * lower-cased, contains one of: `qwen3.5` followed by `4b` or `9b` (separators `.`, `-`, `_`, `:` or a space
 * optional between the parts, and no further digit after the `b`), or `gemma` `4` `12b` together with `qat`
 * anywhere in the same name. Examples: `Qwen_Qwen3.5-4B-Q4_K_M.gguf`, `qwen3.5:9b`,
 * `gemma-4-12b-it-qat-q4_0.gguf` match; `qwen3.5-2b`, `qwen3.5-14b`, `gemma-4-12b-it` do not.
 *
 * **Cleanup.** Gemma 4 E4B: `gemma`, `4` and `e4b` in that order with the same optional separators, and no digit
 * after the `b` (`gemma-4-e4b-it-qat-q4_0`, `gemma-4-E4B_q4_0-it.gguf`, `gemma4:e4b`).
 */
public object ModelChoices {
    /** The name older Telltype Server versions listed for the cleanup model; accepted but no longer listed. */
    public const val DEPRECATED_CLEANUP: String = "cleanup"

    /** The name older Telltype Server versions listed for the command model; accepted but no longer listed. */
    public const val DEPRECATED_COMMAND: String = "assistant"

    private const val SEP = "[-._: ]?"
    private const val GGUF = ".gguf"
    private val QwenSizes = Regex("qwen3${SEP}5$SEP(4|9)b(?![0-9])")
    private val Qwen4 = Regex("qwen3${SEP}5${SEP}4b(?![0-9])")
    private val Gemma12 = Regex("gemma${SEP}4${SEP}12b(?![0-9])")
    private val GemmaE4 = Regex("gemma${SEP}4${SEP}e4b(?![0-9])")
    private val SpeechNames =
        Regex("whisper|parakeet|canary|moonshine|sensevoice|transcri|speech|(^|[^a-z])(stt|asr)([^a-z]|$)")

    /** Whether [model] carries "(Recommended)" in the command list (D58). */
    public fun isRecommended(model: OfferedModel): Boolean = model.names().any { name ->
        QwenSizes.containsMatchIn(name) || (Gemma12.containsMatchIn(name) && "qat" in name)
    }

    /** Whether [model] carries "(Recommended)" in the cleanup list (Gemma 4 E4B). */
    public fun isRecommendedCleanup(model: OfferedModel): Boolean = model.names().any { GemmaE4.containsMatchIn(it) }

    /**
     * The default cleanup model: [serverCleanup] (the model Telltype Server's `/healthz` reports for cleanup)
     * when listed, else the recommended cleanup model, else the first listed; null for an empty list.
     */
    public fun defaultCleanup(models: List<OfferedModel>, serverCleanup: String?): String? = (
        models.firstOrNull { serverCleanup != null && it.id == serverCleanup }
            ?: models.firstOrNull(::isRecommendedCleanup)
            ?: models.firstOrNull()
        )?.id

    /** The default command model: Qwen3.5 4B when listed, else the first recommended, else the first listed. */
    public fun defaultCommand(models: List<OfferedModel>): String? = (
        models.firstOrNull { model -> model.names().any { Qwen4.containsMatchIn(it) } }
            ?: models.firstOrNull(::isRecommended)
            ?: models.firstOrNull()
        )?.id

    /** The default speech model: the first whose name says speech (`whisper`, `parakeet`, …), else null. */
    public fun defaultSpeech(models: List<OfferedModel>?): String? =
        models?.firstOrNull { SpeechNames.containsMatchIn(it.id.lowercase()) }?.id

    /**
     * How a model name is shown: an absolute file path (llama.cpp's id without an alias) by its file name, and a
     * `.gguf` file name without the extension.
     */
    public fun displayName(id: String): String {
        val name = if (id.startsWith('/')) id.substringAfterLast('/') else id
        return if (name.endsWith(GGUF, ignoreCase = true)) name.dropLast(GGUF.length) else name
    }

    /** [chosen] when the server still lists it (or lists nothing), otherwise [fallback]. */
    public fun keepIfListed(chosen: String?, models: List<OfferedModel>?, fallback: String?): String? =
        chosen?.takeIf { models == null || models.any { model -> model.id == it } } ?: fallback

    /**
     * Saved choices with the server's real names in place of the deprecated aliases ([DEPRECATED_CLEANUP],
     * [DEPRECATED_COMMAND]): applied after a successful check that listed [text]. A choice changes only when it is
     * the alias, the list is known and the server does not list the alias itself (a stock server may have a model
     * of that name). Returns [chosen] unchanged otherwise.
     */
    public fun replaceAliases(chosen: ServerModels, text: List<OfferedModel>?, serverCleanup: String?): ServerModels {
        if (text.isNullOrEmpty()) return chosen
        fun replaced(current: String?, alias: String, real: () -> String?): String? =
            if (current == alias && text.none { it.id == alias }) real() ?: current else current
        return chosen.copy(
            cleanup = replaced(chosen.cleanup, DEPRECATED_CLEANUP) { defaultCleanup(text, serverCleanup) },
            command = replaced(chosen.command, DEPRECATED_COMMAND) { defaultCommand(text) },
        )
    }

    private fun OfferedModel.names(): List<String> = listOfNotNull(id, file).map(String::lowercase)
}
