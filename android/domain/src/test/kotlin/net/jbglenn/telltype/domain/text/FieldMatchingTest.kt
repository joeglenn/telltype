package net.jbglenn.telltype.domain.text

import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.FieldKind.MULTI_LINE
import net.jbglenn.telltype.core.model.field.FieldKind.NUMERIC
import net.jbglenn.telltype.core.model.field.FieldKind.PASSWORD
import net.jbglenn.telltype.core.model.field.FieldKind.SINGLE_LINE
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppCategory.EMAIL
import net.jbglenn.telltype.core.model.style.AppCategory.OTHER
import net.jbglenn.telltype.core.model.style.AppCategory.PERSONAL_CHAT
import net.jbglenn.telltype.core.model.style.AppCategory.WORK_CHAT
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.core.model.style.Tone.CASUAL
import net.jbglenn.telltype.core.model.style.Tone.FORMAL
import net.jbglenn.telltype.core.model.style.Tone.VERY_CASUAL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** One row: [text] matched to a field gives [expected]. */
private data class FieldCase(
    val name: String,
    val text: String,
    val expected: String,
    val kind: FieldKind = MULTI_LINE,
    val before: String = "",
    val after: String = "",
    val category: AppCategory = OTHER,
    val tone: Tone = CASUAL,
    val protectedWords: List<String> = emptyList(),
)

/** The field-matching table (docs/PLAN.md section 4.4; at least 60 cases by the `matchField` contract). */
class FieldMatchingTest {
    private val whitespace = listOf(
        FieldCase("plain text is unchanged", "Hello world.", "Hello world."),
        FieldCase("outer and inner spaces collapse", "  Hello   world.  ", "Hello world."),
        FieldCase("tabs collapse", "Hello\tworld.", "Hello world."),
        FieldCase("no-break spaces collapse", "Hello\u00A0\u00A0world.", "Hello world."),
        FieldCase("CRLF becomes LF", "Line one.\r\nLine two.", "Line one.\nLine two."),
        FieldCase("CR becomes LF", "Line one.\rLine two.", "Line one.\nLine two."),
        FieldCase("at most one blank line", "Para one.\n\n\n\nPara two.", "Para one.\n\nPara two."),
        FieldCase("blank lines around are dropped", "\n\nHello.\n\n", "Hello."),
        FieldCase("each line trimmed", "  line  \n  next  ", "line\nnext"),
        FieldCase("blank line with spaces counts as blank", "One.\n   \nTwo.", "One.\n\nTwo."),
        FieldCase("empty stays empty", "", ""),
        FieldCase("whitespace only becomes empty", "   \n\t ", ""),
        FieldCase("empty stays empty after a word", "", "", before = "hello", after = "world"),
        FieldCase("list kept in a multi-line field", "- eggs\n- milk", "- eggs\n- milk"),
    )

    private val singleLine = listOf(
        FieldCase("lines joined, final period dropped", "Line one.\nLine two.", "Line one. Line two", SINGLE_LINE),
        FieldCase("paragraphs joined", "Para one.\n\nPara two.", "Para one. Para two", SINGLE_LINE),
        FieldCase(
            "list items joined with commas",
            "Shopping:\n- eggs\n- milk\n- bread",
            "Shopping: eggs, milk, bread",
            SINGLE_LINE,
        ),
        FieldCase("every bullet kind", "- eggs\n* milk\n• bread", "eggs, milk, bread", SINGLE_LINE),
        FieldCase("item then plain line", "- eggs\nThat is all.", "eggs That is all", SINGLE_LINE),
        FieldCase("final period dropped", "Hello world.", "Hello world", SINGLE_LINE),
        FieldCase("ellipsis kept", "Wait...", "Wait...", SINGLE_LINE),
        FieldCase("abbreviation kept", "See you, e.g.", "See you, e.g.", SINGLE_LINE),
        FieldCase("exclamation kept", "Done!", "Done!", SINGLE_LINE),
        FieldCase("question kept", "Done?", "Done?", SINGLE_LINE),
        FieldCase("formal tone still loses it", "Hello.", "Hello", SINGLE_LINE, tone = FORMAL),
        FieldCase("password field is single-line", "a\nb.", "a b", PASSWORD),
        FieldCase("numeric field is single-line", "1\n2", "1 2", NUMERIC),
    )

    private val finalPeriod = listOf(
        FieldCase("personal chat drops it", "Sounds good.", "Sounds good", category = PERSONAL_CHAT),
        FieldCase("work chat drops it", "Sounds good.", "Sounds good", category = WORK_CHAT),
        FieldCase("formal chat keeps it", "Sounds good.", "Sounds good.", category = PERSONAL_CHAT, tone = FORMAL),
        FieldCase("email keeps it", "Sounds good.", "Sounds good.", category = EMAIL),
        FieldCase("very casual drops it anywhere", "Sounds good.", "Sounds good", tone = VERY_CASUAL),
        FieldCase("formal work chat keeps it", "Sounds good.", "Sounds good.", category = WORK_CHAT, tone = FORMAL),
        FieldCase("two sentences keep it", "First. Second.", "First. Second.", category = PERSONAL_CHAT),
        FieldCase("two lines keep it", "Line one.\nLine two.", "Line one.\nLine two.", category = PERSONAL_CHAT),
        FieldCase(
            "exclamation then a sentence keeps it",
            "Hi! How are you.",
            "Hi! How are you.",
            category = PERSONAL_CHAT,
        ),
        FieldCase("exclamation stays", "Sounds good!", "Sounds good!", category = PERSONAL_CHAT),
        FieldCase("ellipsis stays", "Let's see...", "Let's see...", category = PERSONAL_CHAT),
        FieldCase("abbreviation stays", "Meet at 3 p.m.", "Meet at 3 p.m.", category = PERSONAL_CHAT),
        FieldCase("decimal is no sentence end", "Version 3.5 is out.", "Version 3.5 is out", category = PERSONAL_CHAT),
        FieldCase(
            "title then name is two sentences",
            "Mr. Smith called.",
            "Mr. Smith called.",
            category = PERSONAL_CHAT,
        ),
        FieldCase("before lower-case text", "Hello.", "Hello", after = ", then"),
        FieldCase("before lower-case text after spaces", "Hello.", "Hello", after = "  and more"),
        FieldCase("before a semicolon", "Hello.", "Hello", after = "; more"),
        FieldCase("before a capital it stays", "Hello.", "Hello. ", after = "World"),
    )

    private val continuing = listOf(
        FieldCase("after a word: space and lower case", "World.", " world.", before = "Hello"),
        FieldCase("after a comma", "Thanks.", " thanks.", before = "Dear team,"),
        FieldCase("after a colon", "Eggs and milk.", " eggs and milk.", before = "Buy:"),
        FieldCase("after a semicolon", "Then go.", " then go.", before = "Stop;"),
        FieldCase("after a hyphen", "Check this.", " check this.", before = "Note -"),
        FieldCase("after an em dash", "No.", " no.", before = "Wait —"),
        FieldCase("after a closed em dash: no space", "No.", "no.", before = "Wait—"),
        FieldCase("after a word hyphen: no space", "Known.", "known.", before = "well-"),
        FieldCase("after an open parenthesis: no space", "See below).", "see below).", before = "("),
        FieldCase("after a digit", "Apples.", " apples.", before = "buy 3"),
        FieldCase("after a trailing space", "World.", "world.", before = "Hello "),
        FieldCase("mid-sentence between words", "Mid.", " mid ", before = "x", after = "y"),
        FieldCase("mid-sentence after a space", "That plan.", "that plan ", before = "I think ", after = "is fine"),
        FieldCase("after a period: capital kept", "World.", " World.", before = "Hello."),
        FieldCase("after a sentence and space", "World.", "World.", before = "Hello. "),
        FieldCase("after a line break", "World.", "World.", before = "Hello\n"),
        FieldCase("after a line break and spaces", "Next.", "Next.", before = "line one\n   "),
        FieldCase("after a question mark", "Yes.", " Yes.", before = "Ready?"),
        FieldCase("I stays", "I will go.", " I will go.", before = "and I said"),
        FieldCase("I'm stays", "I'm going.", " I'm going.", before = "and"),
        FieldCase("I’ll stays", "I’ll go.", " I’ll go.", before = "and"),
        FieldCase("I alone stays", "I", " I", before = "abc,"),
        FieldCase("inner capital stays", "GitHub now.", " GitHub now.", before = "send it to"),
        FieldCase("acronym stays", "OK then.", " OK then.", before = "ok"),
        FieldCase("name repeated in the text", "Sarah said, Sarah knows.", " Sarah said, Sarah knows.", before = "and"),
        FieldCase("name in the text before", "Sarah now.", " Sarah now.", before = "Hi Sarah, call"),
        FieldCase("name with no evidence is lowered", "Paris tomorrow.", " paris tomorrow.", before = "fly to"),
        FieldCase(
            "protected word",
            "Kubernetes today.",
            " Kubernetes today.",
            before = "deploy to",
            protectedWords = listOf("Kubernetes"),
        ),
        FieldCase(
            "protected phrase",
            "New York soon.",
            " New York soon.",
            before = "fly to",
            protectedWords = listOf("New York"),
        ),
        FieldCase(
            "protected word must end at a word end",
            "Kubernetes is.",
            " kubernetes is.",
            before = "and",
            protectedWords = listOf("Kube"),
        ),
        FieldCase("blank protected word ignored", "Hello.", " hello.", before = "and", protectedWords = listOf(" ")),
        FieldCase("accented capital lowered", "Éclair time.", " éclair time.", before = "an"),
        FieldCase("starts with a symbol", "\$5.", " \$5.", before = "it costs"),
        FieldCase("emoji first", "😀 yes.", " 😀 yes.", before = "hi"),
    )

    private val spacing = listOf(
        FieldCase("comma attaches left", ", world.", ", world.", before = "hello"),
        FieldCase("exclamation attaches left", "!", "!", before = "hello"),
        FieldCase("closing quote before", "Then left.", " Then left.", before = "Said \"hi\""),
        FieldCase("opening quote before", "Hi.", "Hi.", before = "Said \""),
        FieldCase("after a percent sign", "Done.", " Done.", before = "50%"),
        FieldCase("after a closing parenthesis", "Next.", " Next.", before = "(see)"),
        FieldCase("after a typographic quote", "Next.", " Next.", before = "“hi”"),
        FieldCase("word after: trailing space", "Hello", "Hello ", after = "world"),
        FieldCase("space after: none added", "Hello", "Hello", after = " world"),
        FieldCase("punctuation after: none added", "Hello", "Hello", after = "!"),
        FieldCase("digit after: trailing space", "Room", "Room ", after = "5"),
    )

    private val cases = whitespace + singleLine + finalPeriod + continuing + spacing

    @Test
    fun `the table has at least 60 cases with unique names`() {
        assertTrue(cases.size >= MIN_CASES, "only ${cases.size} cases")
        assertEquals(cases.size, cases.map { it.name }.toSet().size)
        println("matchField: ${cases.size} cases")
    }

    @TestFactory
    fun `matchField table`(): List<DynamicTest> = cases.map { case ->
        dynamicTest(case.name) {
            val input = FieldMatchInput(
                case.kind,
                case.before,
                case.after,
                case.category,
                case.tone,
                case.protectedWords,
            )
            assertEquals(case.expected, matchField(case.text, input))
        }
    }

    @Test
    fun `input toString hides the field text`() {
        val input = FieldMatchInput(MULTI_LINE, "secret before", "secret after", OTHER, CASUAL, emptyList())
        assertTrue("secret" !in input.toString(), input.toString())
    }

    private companion object {
        const val MIN_CASES = 60
    }
}
