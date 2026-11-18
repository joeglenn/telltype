package net.jbglenn.telltype.domain.cleanup

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.cleanup.EchoSource
import net.jbglenn.telltype.core.model.cleanup.Guard
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.cleanup.LengthViolation
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.Mismatches
import net.jbglenn.telltype.domain.SharedAssets
import net.jbglenn.telltype.domain.Vectors
import net.jbglenn.telltype.domain.items
import net.jbglenn.telltype.domain.string
import net.jbglenn.telltype.domain.strings
import net.jbglenn.telltype.domain.text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Replays every vector exported by `eval/export_conformance.py` from the Python reference (`eval/guards.py`,
 * `eval/prompt_builder.py`) and demands the identical result from the Kotlin implementation.
 */
class ConformanceTest {
    private val rules = SharedAssets.rules
    private val prompts = SharedAssets.prompts

    @Test
    fun `vectors were exported from the shared files in use`() {
        listOf("prompts.json", "guards.json", "fallback.json", "tokens.json").forEach { name ->
            val file = Vectors.load(name)
            assertEquals(prompts.version.toString(), file.text("prompt_version"), name)
            assertEquals(rules.version, file.getValue("guards_version").jsonPrimitive.int, name)
        }
    }

    @Test
    fun `cleanup requests are identical to prompt_builder`() {
        val file = Vectors.load("prompts.json")
        val systems = file.getValue("system_messages").jsonObject
        val mismatches = Mismatches()
        val vectors = file.items("cleanup")
        vectors.forEach { vector ->
            val request = vector.getValue("request").jsonObject
            val built = buildCleanupMessages(cleanupRequest(request), prompts)
            val id = vector.text("source")
            compareBody(id, vector.getValue("body").jsonObject, systems, built.chat, mismatches)
            val inputs = vector.getValue("guard_inputs").jsonObject
            val expected = GuardInputs(
                transcript = inputs.text("transcript"),
                systemPrompt = systems.text(inputs.text("system_prompt")),
                toneLine = inputs.text("tone_line"),
                dictionary = inputs.strings("dictionary"),
                contextBefore = inputs.text("context_before"),
            )
            mismatches.compare(id, "guard inputs", expected, built.guardInputs)
        }
        mismatches.assertNone()
        assertTrue(vectors.size >= MIN_CLEANUP_REQUESTS, "only ${vectors.size} cleanup vectors")
        println("conformance: ${vectors.size} cleanup requests, all matched")
    }

    @Test
    fun `command requests are identical to prompt_builder`() {
        val file = Vectors.load("prompts.json")
        val systems = file.getValue("system_messages").jsonObject
        val mismatches = Mismatches()
        val vectors = file.items("command")
        vectors.forEach { vector ->
            val request = vector.getValue("request").jsonObject
            val built = buildCommandMessages(
                CommandRequest(SessionId("conformance"), request.text("selection"), request.text("instruction")),
                prompts,
            )
            compareBody(vector.text("source"), vector.getValue("body").jsonObject, systems, built, mismatches)
        }
        mismatches.assertNone()
        println("conformance: ${vectors.size} command requests, all matched")
    }

    @Test
    fun `reply extraction, each guard and the verdict are identical to guards_py`() {
        val file = Vectors.load("guards.json")
        val systems = file.getValue("system_messages").jsonObject
        val mismatches = Mismatches()
        val vectors = file.items("cases")
        vectors.forEach { vector -> checkGuardVector(vector, systems, mismatches) }
        mismatches.assertNone()
        assertTrue(vectors.size >= MIN_GUARD_CASES, "only ${vectors.size} guard vectors")
        val rejected = vectors.count { !it.getValue("verdict").jsonObject.getValue("passed").jsonPrimitive.boolean }
        println(
            "conformance: ${vectors.size} guard cases ($rejected rejected), ${mismatches.checked} checks, all matched",
        )
    }

    @Test
    fun `rule-based fallback is identical to guards_py`() {
        val mismatches = Mismatches()
        val vectors = Vectors.load("fallback.json").items("cases")
        vectors.forEachIndexed { index, vector ->
            val transcript = vector.text("transcript")
            mismatches.compare("fallback $index", "text", vector.text("fallback"), ruleBasedCleanup(transcript, rules))
        }
        mismatches.assertNone()
        println("conformance: ${vectors.size} fallback cases, all matched")
    }

    @Test
    fun `tokens, spoken numbers and units are identical to guards_py`() {
        val mismatches = Mismatches()
        val vectors = Vectors.load("tokens.json").items("cases")
        vectors.forEachIndexed { index, vector ->
            val tokens = tokensOf(vector.text("text"), rules)
            val expectedTokens = vector.getValue("tokens").jsonArray.map { pair ->
                pair.jsonArray[0].jsonPrimitive.content to pair.jsonArray[1].jsonPrimitive.content
            }
            mismatches.compare(
                "tokens $index",
                "tokens",
                expectedTokens,
                tokens.map {
                    it.text to
                        it.kind.name.lowercase()
                },
            )
            mismatches.compare(
                "tokens $index",
                "spoken numbers",
                vector.strings("spoken_numbers").toSet(),
                spokenNumberValues(tokens, rules),
            )
            mismatches.compare(
                "tokens $index",
                "units",
                vector.getValue("units").jsonPrimitive.int,
                transcriptUnits(tokens, rules),
            )
        }
        mismatches.assertNone()
        println("conformance: ${vectors.size} token cases, all matched")
    }

    private fun cleanupRequest(request: JsonObject) = CleanupRequest(
        session = SessionId("conformance"),
        transcript = request.text("transcript"),
        tone = Tone.entries.single { it.id == request.text("tone") },
        category = AppCategory.entries.single { it.id == request.text("category") },
        dictionary = request.strings("dictionary"),
        contextBefore = request.text("context_before"),
        fieldHint = request.string("field_hint"),
    )

    private fun compareBody(
        id: String,
        body: JsonObject,
        systems: JsonObject,
        chat: ChatPrompt,
        mismatches: Mismatches,
    ) {
        mismatches.compare(id, "model", body.text("model"), chat.model)
        mismatches.compare(id, "stream", false, body.getValue("stream").jsonPrimitive.boolean)
        mismatches.compare(id, "temperature", body.getValue("temperature").jsonPrimitive.double, chat.temperature)
        mismatches.compare(id, "max_tokens", body.getValue("max_tokens").jsonPrimitive.int, chat.maxTokens)
        val kwargs = body.getValue("chat_template_kwargs").jsonObject
        mismatches.compare(id, "kwargs", setOf("enable_thinking"), kwargs.keys)
        mismatches.compare(
            id,
            "thinking",
            kwargs.getValue("enable_thinking").jsonPrimitive.boolean,
            chat.enableThinking,
        )
        val expectedMessages = listOf(
            ChatMessage(ChatRole.SYSTEM, systems.text(body.text("system"))),
            ChatMessage(ChatRole.USER, body.text("user")),
        )
        mismatches.compare(id, "roles", expectedMessages.map { it.role }, chat.messages.map { it.role })
        mismatches.compare(id, "system", expectedMessages[0].content, chat.messages[0].content)
        mismatches.compare(id, "user", expectedMessages[1].content, chat.messages.getOrNull(1)?.content)
    }

    private fun checkGuardVector(vector: JsonObject, systems: JsonObject, mismatches: Mismatches) {
        val id = vector.text("id") + " " + vector.text("source")
        val inputs = vector.getValue("inputs").jsonObject
        val guardInputs = GuardInputs(
            transcript = inputs.text("transcript"),
            systemPrompt = systems.string(inputs.text("system_prompt")).orEmpty(),
            toneLine = inputs.text("tone_line"),
            dictionary = inputs.strings("dictionary"),
            contextBefore = inputs.text("context_before"),
        )
        val reply = ModelReply(vector.string("content"), vector.string("finish_reason"), emptyList())
        val extracted = extractReply(reply, guardInputs.transcript, rules.replyExtraction)
        mismatches.compare(id, "extracted", vector.text("extracted"), extracted)
        val subject = GuardSubject(extracted, guardInputs, reply.finishReason, rules)
        val each = vector.getValue("guards").jsonObject
        Guard.entries.forEach { guard ->
            val expected = expectedVerdict(guard, each.getValue(guard.id).jsonObject, extracted)
            val actual = runGuard(guard, subject, rules) ?: GuardVerdict.Accepted(extracted)
            mismatches.compare(id, guard.id, expected, actual)
        }
        val verdict = vector.getValue("verdict").jsonObject
        val expectedOverall = verdict.string("guard")?.let { name ->
            expectedVerdict(Guard.entries.single { it.id == name }, verdict, extracted)
        } ?: GuardVerdict.Accepted(extracted)
        mismatches.compare(id, "verdict", expectedOverall, applyGuards(reply, guardInputs, rules))
    }

    /** The Kotlin verdict a vector's guard result stands for. */
    private fun expectedVerdict(guard: Guard, result: JsonObject, extracted: String): GuardVerdict =
        if (result.getValue("passed").jsonPrimitive.boolean) {
            GuardVerdict.Accepted(extracted)
        } else {
            when (guard) {
                Guard.EMPTY -> GuardVerdict.EmptyReply

                Guard.ASSISTANT_PHRASING -> GuardVerdict.AssistantPhrasing(result.text("phrase"))

                Guard.LENGTH -> GuardVerdict.LengthOutOfBounds(
                    LengthViolation.valueOf(result.text("violation").uppercase()),
                    result.getValue("reply_tokens").jsonPrimitive.int,
                    result.string("bound")?.toDouble(),
                )

                Guard.UNSPOKEN_WORDS -> GuardVerdict.UnspokenWords(
                    result.strings("words"),
                    result.getValue("allowance").jsonPrimitive.int,
                )

                Guard.ECHO -> GuardVerdict.Echo(EchoSource.valueOf(result.text("source").uppercase()))

                Guard.SNIPPET_MARKERS -> GuardVerdict.SnippetMarkersChanged(
                    counts(result.getValue("expected").jsonObject),
                    counts(result.getValue("actual").jsonObject),
                )
            }
        }

    private fun counts(json: JsonObject): Map<String, Int> = json.mapValues { it.value.jsonPrimitive.int }

    private companion object {
        const val MIN_CLEANUP_REQUESTS = 140
        const val MIN_GUARD_CASES = 1500
    }
}
