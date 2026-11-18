package net.jbglenn.telltype.domain.cleanup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.domain.SharedAssets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** [json] with the member at the dotted [path] replaced by [value], or removed when [value] is null. */
internal fun edited(json: String, path: String, value: JsonElement?): String {
    fun edit(element: JsonElement, keys: List<String>): JsonElement {
        val obj = element.jsonObject.toMutableMap()
        val key = keys.first()
        if (keys.size == 1) {
            if (value == null) obj.remove(key) else obj[key] = value
        } else {
            obj[key] = edit(obj.getValue(key), keys.drop(1))
        }
        return JsonObject(obj)
    }
    return edit(Json.parseToJsonElement(json), path.split('.')).toString()
}

private fun strings(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

/** The shared-file parsers accept the shipped files and reject every malformed variant with the file named. */
class AssetParsingTest {
    private val guards = SharedAssets.guardsJson
    private val promptFiles = SharedAssets.promptFiles

    @Test
    fun `shipped files parse`() {
        val rules = SharedAssets.rules
        assertEquals(6, rules.order.size)
        assertEquals("⟦", rules.snippetMarkers.open)
        val prompts = SharedAssets.prompts
        assertTrue(prompts.cleanupUserTemplate.endsWith("</transcript>"), "trailing newline removed")
        assertEquals(500, prompts.contextBeforeMaxChars)
    }

    @TestFactory
    fun `malformed guards json is rejected`(): List<DynamicTest> = listOf(
        "not json" to "{",
        "root not an object" to "[]",
        "unknown top-level key" to edited(guards, "extra", JsonPrimitive(1)),
        "missing section" to edited(guards, "echo", null),
        "version not an integer" to edited(guards, "version", JsonPrimitive("2")),
        "version fractional" to edited(guards, "version", JsonPrimitive(2.5)),
        "unknown guard in order" to edited(guards, "order", strings("empty", "nonsense")),
        "guard twice in order" to edited(guards, "order", strings("empty", "empty")),
        "order not an array" to edited(guards, "order", JsonPrimitive("empty")),
        "empty think open" to edited(guards, "reply_extraction.strip_leading_think_block.open", JsonPrimitive("")),
        "entity repeated" to edited(
            guards,
            "reply_extraction.unescape_entities",
            JsonArray(listOf(strings("&lt;", "<"), strings("&lt;", "<"))),
        ),
        "entity not a pair" to edited(guards, "reply_extraction.unescape_entities", JsonArray(listOf(strings("&lt;")))),
        "quote pair with an empty side" to edited(
            guards,
            "reply_extraction.wrapping_quote_pairs",
            JsonArray(listOf(strings("\"", ""))),
        ),
        "unknown unicode form" to edited(guards, "normalisation.unicode_form", JsonPrimitive("NFX")),
        "negative list digits" to edited(guards, "normalisation.numbered_list_max_digits", JsonPrimitive(-1)),
        "empty phrase" to edited(guards, "assistant_phrasing.anywhere", strings("as an ai", "")),
        "ratio is a string" to edited(guards, "length.upper_ratio", JsonPrimitive("1.6")),
        "flag is a number" to edited(guards, "unspoken_words.ignore_single_character_tokens", JsonPrimitive(1)),
        "equivalent not a pair" to edited(
            guards,
            "unspoken_words.equivalents",
            JsonArray(listOf(JsonArray(emptyList()))),
        ),
        "empty contraction suffix" to edited(
            guards,
            "unspoken_words.contractions.suffixes",
            JsonObject(mapOf("" to strings("not"))),
        ),
        "number word value not an integer" to edited(
            guards,
            "unspoken_words.number_words.units",
            JsonObject(mapOf("one" to JsonPrimitive("1"))),
        ),
        "negative max run" to edited(guards, "unspoken_words.number_words.max_run_words", JsonPrimitive(-1)),
        "ngram size zero" to edited(guards, "echo.ngram_size", JsonPrimitive(0)),
        "empty marker open" to edited(guards, "snippet_markers.open", JsonPrimitive("")),
        "marker digits zero" to edited(guards, "snippet_markers.max_digits", JsonPrimitive(0)),
        "metacharacter in marker" to edited(guards, "snippet_markers.prefix", JsonPrimitive("S+")),
        "digit in marker" to edited(guards, "snippet_markers.prefix", JsonPrimitive("S1")),
        "regex disagrees" to edited(guards, "snippet_markers.regex", JsonPrimitive("⟦S[0-9]{1,3}⟧")),
        "fallback list missing" to edited(guards, "fallback.fillers", null),
        "unknown fallback key" to edited(guards, "fallback.extra", JsonPrimitive("x")),
    ).map { (name, json) ->
        dynamicTest(name) {
            val outcome = parseGuardRules(json)
            assertTrue(outcome is Outcome.Err, "$name was accepted")
            assertEquals("guards.json", (outcome as Outcome.Err).error.file)
            assertTrue(outcome.error.problem.isNotBlank())
        }
    }

    @Test
    fun `guards json with CRLF line endings parses`() {
        assertTrue(parseGuardRules(guards.replace("\n", "\r\n")) is Outcome.Ok)
    }

    private fun promptsWith(name: String, content: String?): Map<String, String> =
        if (content == null) promptFiles - name else promptFiles + (name to content)

    private fun requestWith(path: String, value: JsonElement?) =
        promptsWith("request.json", edited(promptFiles.getValue("request.json"), path, value))

    private val promptCases = listOf(
        Triple("VERSION missing", promptsWith("VERSION", null), "prompts/VERSION"),
        Triple("VERSION not a number", promptsWith("VERSION", "two\n"), "prompts/VERSION"),
        Triple("system prompt missing", promptsWith("cleanup_system.txt", null), "prompts/cleanup_system.txt"),
        Triple(
            "cleanup placeholder missing",
            promptsWith("cleanup_user.tmpl", "<transcript>{{transcript}}</transcript>"),
            "prompts/cleanup_user.tmpl",
        ),
        Triple(
            "unknown command placeholder",
            promptsWith("command_user.tmpl", "{{selection}} {{instruction}} {{extra}}"),
            "prompts/command_user.tmpl",
        ),
        Triple("tones missing", promptsWith("tones.json", null), "prompts/tones.json"),
        Triple("tones not json", promptsWith("tones.json", "{"), "prompts/tones.json"),
        Triple(
            "tone default unknown",
            promptsWith("tones.json", edited(promptFiles.getValue("tones.json"), "default", JsonPrimitive("shouty"))),
            "prompts/tones.json",
        ),
        Triple(
            "a tone missing",
            promptsWith("tones.json", edited(promptFiles.getValue("tones.json"), "tones.formal", null)),
            "prompts/tones.json",
        ),
        Triple(
            "category default unknown",
            promptsWith(
                "categories.json",
                edited(promptFiles.getValue("categories.json"), "default", JsonPrimitive("games")),
            ),
            "prompts/categories.json",
        ),
        Triple(
            "extra category",
            promptsWith(
                "categories.json",
                edited(promptFiles.getValue("categories.json"), "categories.games", JsonPrimitive("Games")),
            ),
            "prompts/categories.json",
        ),
        Triple("request missing", promptsWith("request.json", null), "prompts/request.json"),
        Triple("stream true", requestWith("cleanup.stream", JsonPrimitive(true)), "prompts/request.json"),
        Triple(
            "unknown chat template kwarg",
            requestWith("command.chat_template_kwargs.reasoning", JsonPrimitive(false)),
            "prompts/request.json",
        ),
        Triple(
            "chars per token zero",
            requestWith("cleanup.max_tokens.chars_per_token", JsonPrimitive(0)),
            "prompts/request.json",
        ),
        Triple("max below min", requestWith("command.max_tokens.max", JsonPrimitive(1)), "prompts/request.json"),
        Triple(
            "negative context",
            requestWith("cleanup.context_before_max_chars", JsonPrimitive(-1)),
            "prompts/request.json",
        ),
        Triple(
            "context on command",
            requestWith("command.context_before_max_chars", JsonPrimitive(5)),
            "prompts/request.json",
        ),
        Triple("model missing", requestWith("cleanup.model", null), "prompts/request.json"),
        Triple(
            "temperature a string",
            requestWith("cleanup.temperature", JsonPrimitive("0.1")),
            "prompts/request.json",
        ),
    )

    @TestFactory
    fun `malformed prompt files are rejected`(): List<DynamicTest> = promptCases.map { (name, files, file) ->
        dynamicTest(name) {
            val outcome = parsePromptSet(files)
            assertTrue(outcome is Outcome.Err, "$name was accepted")
            assertEquals(file, (outcome as Outcome.Err).error.file)
        }
    }

    @Test
    fun `the first problem is reported`() {
        val files = promptFiles - "VERSION" - "request.json"
        assertEquals("prompts/VERSION", (parsePromptSet(files) as Outcome.Err).error.file)
    }

    @Test
    fun `line endings are read like Python text mode and one final newline is removed`() {
        val system = "Line one.\r\nLine two.\r\n\n"
        val parsed = parsePromptSet(promptsWith("cleanup_system.txt", system)) as Outcome.Ok
        assertEquals("Line one.\nLine two.\n", parsed.value.cleanupSystem)
        val old = parsePromptSet(promptsWith("cleanup_system.txt", "Mac\rline")) as Outcome.Ok
        assertEquals("Mac\nline", old.value.cleanupSystem)
    }
}
