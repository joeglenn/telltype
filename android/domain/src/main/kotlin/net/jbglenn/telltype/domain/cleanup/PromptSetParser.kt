package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.flatMap
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/** `{{name}}` with `name` in `[a-z_]+` (`shared/prompts/README.md`, "Placeholders"). */
internal val PlaceholderPattern: Regex = Regex("""\{\{([a-z_]+)\}\}""")

/** The placeholders `cleanup_user.tmpl` must contain, exactly. */
internal val CleanupPlaceholders: Set<String> = setOf("tone", "dictionary", "app", "field", "before", "transcript")

/** The placeholders `command_user.tmpl` must contain, exactly. */
internal val CommandPlaceholders: Set<String> = setOf("selection", "instruction")

/** Names used in error messages. */
internal object PromptFiles {
    /** Directory of the prompt files inside `shared/`. */
    const val DIR = "prompts/"
}

/** Parses the `shared/prompts/` files ([files]: name to UTF-8 content) into a [PromptSet]. */
internal fun decodePromptSet(files: Map<String, String>): Outcome<PromptSet, AssetError> {
    val problems = FirstProblem()
    val version = problems.take(decodeVersion(files), 0)
    val texts = problems.take(messages(files), emptyMap())
    val tones = problems.take(decodeTones(files), emptyMap())
    val categories = problems.take(decodeCategories(files), emptyMap())
    val request = problems.take(decodeRequest(files), NoRequest)
    return problems.finish {
        PromptSet(
            version = version,
            cleanupSystem = texts.getValue("cleanup_system.txt"),
            cleanupUserTemplate = texts.getValue("cleanup_user.tmpl"),
            commandSystem = texts.getValue("command_system.txt"),
            commandUserTemplate = texts.getValue("command_user.tmpl"),
            toneLines = tones,
            categoryLabels = categories,
            cleanupRequest = request.cleanup,
            commandRequest = request.command,
            contextBeforeMaxChars = request.contextBeforeMaxChars,
        )
    }
}

/** Keeps the first error of several independent decoding steps. */
private class FirstProblem {
    private var first: AssetError? = null

    /** The value of [outcome], or [fallback] after recording its error. */
    fun <T> take(outcome: Outcome<T, AssetError>, fallback: T): T = when (outcome) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> fallback.also { report(outcome.error) }
    }

    /** Records [error] unless an earlier one is kept. */
    fun report(error: AssetError?) {
        if (first == null) first = error
    }

    /** Builds the value only when every step succeeded. */
    fun <T> finish(build: () -> T): Outcome<T, AssetError> = first?.let { Outcome.Err(it) } ?: Outcome.Ok(build())
}

private fun decodeVersion(files: Map<String, String>): Outcome<Int, AssetError> =
    messageFile(files, "VERSION").flatMap { text ->
        pythonStrip(text).toIntOrNull()?.let { Outcome.Ok(it) }
            ?: Outcome.Err(AssetError(PromptFiles.DIR + "VERSION", "is not an integer"))
    }

private val MessageFiles = listOf("cleanup_system.txt", "cleanup_user.tmpl", "command_system.txt", "command_user.tmpl")

/** The four message files, the two templates checked for their placeholder sets. */
private fun messages(files: Map<String, String>): Outcome<Map<String, String>, AssetError> {
    val problems = FirstProblem()
    val texts = MessageFiles.associateWith { problems.take(messageFile(files, it), "") }
    problems.report(placeholderProblem("cleanup_user.tmpl", texts.getValue("cleanup_user.tmpl"), CleanupPlaceholders))
    problems.report(placeholderProblem("command_user.tmpl", texts.getValue("command_user.tmpl"), CommandPlaceholders))
    return problems.finish { texts }
}

private fun placeholderProblem(name: String, template: String, expected: Set<String>): AssetError? {
    val found = PlaceholderPattern.findAll(template).map { it.groupValues[1] }.toSet()
    return if (found == expected) {
        null
    } else {
        AssetError(PromptFiles.DIR + name, "placeholders ${found.sorted()} != expected ${expected.sorted()}")
    }
}

/**
 * A message file's text: line endings read like Python's text mode (`\r\n` and `\r` become `\n`), then exactly
 * one trailing `\n` removed if present (`shared/prompts/README.md`, "Files").
 */
private fun messageFile(files: Map<String, String>, name: String): Outcome<String, AssetError> {
    val content = files[name] ?: return Outcome.Err(AssetError(PromptFiles.DIR + name, "is missing"))
    return Outcome.Ok(universalNewlines(content).removeSuffix("\n"))
}

/** Python text-mode newline translation. */
internal fun universalNewlines(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

/** A decoder for the JSON file [name] of [files] and its root (a missing file is a problem). */
internal fun jsonFile(files: Map<String, String>, name: String): Pair<AssetDecoder, JsonNode> {
    val decoder = AssetDecoder(PromptFiles.DIR + name)
    val content = files[name]
    decoder.expect(content != null) { "is missing" }
    return decoder to decoder.root(content.orEmpty())
}

private fun decodeTones(files: Map<String, String>): Outcome<Map<Tone, String>, AssetError> {
    val (decoder, root) = jsonFile(files, "tones.json")
    decoder.members(root, setOf("version", "default", "tones"))
    decoder.int(root["version"])
    val default = decoder.string(root["default"])
    decoder.expect(Tone.entries.any { it.id == default }) { "default '$default' is not a tone id" }
    val lines = decoder.members(root["tones"]).associate { (id, line) -> id to decoder.string(line) }
    decoder.expect(lines.keys == Tone.entries.map { it.id }.toSet()) { "tones ${lines.keys} are not the tone ids" }
    return decoder.finish(Tone.entries.associateWith { lines[it.id].orEmpty() })
}

private fun decodeCategories(files: Map<String, String>): Outcome<Map<AppCategory, String>, AssetError> {
    val (decoder, root) = jsonFile(files, "categories.json")
    decoder.members(root, setOf("version", "default", "categories"))
    decoder.int(root["version"])
    val default = decoder.string(root["default"])
    decoder.expect(AppCategory.entries.any { it.id == default }) { "default '$default' is not a category id" }
    val labels = decoder.members(root["categories"]).associate { (id, label) -> id to decoder.string(label) }
    val ids = AppCategory.entries.map { it.id }.toSet()
    decoder.expect(labels.keys == ids) { "categories ${labels.keys} are not the category ids" }
    return decoder.finish(AppCategory.entries.associateWith { labels[it.id].orEmpty() })
}
