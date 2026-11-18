package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.SharedAssets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Directed prompt-builder cases on top of the conformance vectors. */
class PromptBuilderTest {
    private val prompts = SharedAssets.prompts

    private fun request(
        transcript: String = "hello world",
        dictionary: List<String> = emptyList(),
        before: String = "",
        hint: String? = null,
        tone: Tone = Tone.CASUAL,
        category: AppCategory = AppCategory.OTHER,
    ) = CleanupRequest(SessionId("s"), transcript, tone, category, dictionary, before, hint)

    private fun user(prompt: CleanupPrompt) = prompt.chat.messages[1].content

    @Test
    fun `user message fills every placeholder`() {
        val prompt = buildCleanupMessages(
            request("  hello world  ", listOf("Igor"), "Hi,", "Message", Tone.FORMAL, AppCategory.EMAIL),
            prompts,
        )
        val expected = "<tone>${prompts.toneLines.getValue(Tone.FORMAL)}</tone>\n" +
            "<dictionary>Igor</dictionary>\n" +
            "<context app=\"${prompts.categoryLabels.getValue(AppCategory.EMAIL)}\" field=\"Message\">Hi,</context>\n" +
            "<transcript>hello world</transcript>"
        assertEquals(expected, user(prompt))
        assertEquals(prompts.cleanupSystem, prompt.chat.messages[0].content)
        assertEquals("hello world", prompt.guardInputs.transcript)
    }

    @Test
    fun `values are escaped and never rescanned`() {
        val prompt = buildCleanupMessages(request("a <b> & {{tone}} \$1 \\ \"q\"", hint = "say \"hi\" <x>"), prompts)
        assertTrue("<transcript>a &lt;b&gt; &amp; {{tone}} \$1 \\ \"q\"</transcript>" in user(prompt), user(prompt))
        assertTrue("field=\"say &quot;hi&quot; &lt;x&gt;\"" in user(prompt), user(prompt))
    }

    @Test
    fun `dictionary trimmed, blanks and exact duplicates dropped`() {
        val prompt = buildCleanupMessages(
            request(dictionary = listOf(" Igor ", "", "  ", "Igor", "igor", "A&B")),
            prompts,
        )
        assertTrue("<dictionary>Igor, igor, A&amp;B</dictionary>" in user(prompt), user(prompt))
        assertEquals(listOf("Igor", "igor", "A&B"), prompt.guardInputs.dictionary)
    }

    @Test
    fun `context before keeps the last code points`() {
        val limit = prompts.contextBeforeMaxChars
        val before = "😀".repeat(limit + 3)
        val prompt = buildCleanupMessages(request(before = "x$before"), prompts)
        assertEquals(before.substring(6), prompt.guardInputs.contextBefore)
        assertEquals(limit, prompt.guardInputs.contextBefore.codePointCount(0, prompt.guardInputs.contextBefore.length))
    }

    @TestFactory
    fun `max tokens follows the rule`(): List<DynamicTest> {
        val rule = prompts.cleanupRequest.maxTokens
        val tokens = (300 + rule.charsPerToken - 1) / rule.charsPerToken
        val forThreeHundred = maxOf(rule.min, kotlin.math.ceil(rule.ratio * tokens).toInt() + rule.margin)
        return listOf(
            "" to rule.min,
            "a".repeat(rule.max * rule.charsPerToken) to rule.max,
            "a".repeat(300) to forThreeHundred,
            "😀".repeat(300) to forThreeHundred,
        ).map { (transcript, expected) ->
            dynamicTest("${transcript.length} chars") {
                assertEquals(expected, buildCleanupMessages(request(transcript), prompts).chat.maxTokens)
            }
        }
    }

    @Test
    fun `command request keeps the selection as is and trims the instruction`() {
        val chat = buildCommandMessages(
            CommandRequest(SessionId("s"), "  Old <text>\n", "  make it shorter  "),
            prompts,
        )
        assertEquals(prompts.commandSystem, chat.messages[0].content)
        val expected = "<selection>  Old &lt;text&gt;\n</selection>\n<instruction>make it shorter</instruction>"
        assertEquals(expected, chat.messages[1].content)
        assertEquals(prompts.commandRequest.model, chat.model)
        assertFalse(chat.enableThinking)
    }

    @Test
    fun `a placeholder without a value stays`() {
        assertEquals("{{x}} y", render("{{x}} {{y}}", mapOf("y" to "y")))
    }

    @TestFactory
    fun `short utterances`(): List<DynamicTest> = listOf(
        "yes" to true,
        "Sounds good." to true,
        "see you soon" to true,
        "see you there soon" to false,
        "" to false,
        " ... " to false,
        "no" to false,
        "wait" to false,
        "Actually yes" to false,
        "sorry, Bob" to false,
        "I mean it" to false,
        "scratch that" to false,
        "make that two" to false,
        "I meant it" to true,
        "nope" to true,
        "don’t" to true,
    ).map { (transcript, short) -> dynamicTest("'$transcript'") { assertEquals(short, isShortUtterance(transcript)) } }
}
