package net.jbglenn.telltype.domain.text

import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.domain.SharedAssets
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/** Snippets (plan 4.6), exact replacements (plan 8.4) and learnable corrections (plan 4.5). */
class TextFunctionsTest {
    private val markers = SharedAssets.rules.snippetMarkers
    private val address = snippet(1, "my address", "12 High Street\nLondon")
    private val signOff = snippet(2, "sign-off", "Best,\nAlex")
    private val myAddressLong = snippet(3, "my address in full", "12 High Street, London, UK")
    private val snippets = listOf(address, signOff, myAddressLong)

    private fun snippet(id: Long, trigger: String, text: String) = Snippet(
        SnippetId(id),
        trigger,
        text,
        0,
        Fixtures.Epoch,
    )

    private fun replacement(id: Long, heard: String, replaceWith: String) =
        Replacement(ReplacementId(id), heard, replaceWith, EntrySource.MANUAL, 0, Fixtures.Epoch)

    @TestFactory
    fun `whole utterance triggers`(): List<DynamicTest> = listOf(
        "my address" to address,
        "My address." to address,
        "  MY   ADDRESS!  " to address,
        "sign off" to signOff,
        "Sign-off" to signOff,
        "my address in full" to myAddressLong,
    ).map { (transcript, expected) ->
        dynamicTest(transcript) {
            assertEquals(SnippetMatch.WholeUtterance(expected), matchSnippet(transcript, snippets, markers))
        }
    }

    @Test
    fun `no trigger`() {
        assertEquals(SnippetMatch.None, matchSnippet("hello there", snippets, markers))
        assertEquals(SnippetMatch.None, matchSnippet("", snippets, markers))
        assertEquals(SnippetMatch.None, matchSnippet("my addresses", snippets, markers))
        assertEquals(SnippetMatch.None, matchSnippet("hello", emptyList(), markers))
        assertEquals(SnippetMatch.None, matchSnippet("hello", listOf(snippet(9, " ... ", "x")), markers))
    }

    @Test
    fun `embedded triggers become markers numbered by first appearance`() {
        val match = matchSnippet("send it to my address, sign off and again my address", snippets, markers)
        assertEquals(
            SnippetMatch.Embedded(
                "send it to ⟦S1⟧, ⟦S2⟧ and again ⟦S1⟧",
                listOf(SnippetMarker("⟦S1⟧", address), SnippetMarker("⟦S2⟧", signOff)),
            ),
            match,
        )
    }

    @Test
    fun `longest trigger wins`() {
        val match = matchSnippet("here is my address in full thanks", snippets, markers) as SnippetMatch.Embedded
        assertEquals("here is ⟦S1⟧ thanks", match.markedTranscript)
        assertEquals(listOf(SnippetMarker("⟦S1⟧", myAddressLong)), match.markers)
    }

    @Test
    fun `marker brackets in the transcript are removed first`() {
        val match = matchSnippet("⟦S1⟧ is my address", snippets, markers) as SnippetMatch.Embedded
        assertEquals("S1 is ⟦S1⟧", match.markedTranscript)
    }

    @Test
    fun `apostrophes inside words and typographic apostrophes`() {
        val dont = snippet(4, "don't forget", "Remember!")
        val match = matchSnippet("well don’t forget it", listOf(dont), markers) as SnippetMatch.Embedded
        assertEquals("well ⟦S1⟧ it", match.markedTranscript)
    }

    @Test
    fun `no markers when the rules allow no digits`() {
        val none = markers.copy(maxDigits = 0)
        assertEquals(SnippetMatch.None, matchSnippet("send my address", snippets, none))
        val one = markers.copy(maxDigits = 1)
        val many = (1L..12L).map { snippet(it, "word$it", "text $it") }
        val match = matchSnippet((1..12).joinToString(" ") { "say word$it" }, many, one) as SnippetMatch.Embedded
        assertEquals(9, match.markers.size)
        assertTrue("word10" in match.markedTranscript && "⟦S9⟧" in match.markedTranscript)
    }

    @Test
    fun `restore replaces each marker once and ignores missing ones`() {
        val match = SnippetMatch.Embedded(
            "x",
            listOf(SnippetMarker("⟦S1⟧", address), SnippetMarker("⟦S2⟧", signOff), SnippetMarker("", address)),
        )
        assertEquals("Send to 12 High Street\nLondon. ⟦S3⟧", restoreSnippets("Send to ⟦S1⟧. ⟦S3⟧", match))
        assertEquals("Best,\nAlex Best,\nAlex", restoreSnippets("⟦S2⟧ ⟦S2⟧", match))
        assertEquals("", restoreSnippets("", match))
    }

    @Test
    fun `restored text containing a marker is not restored again`() {
        val tricky = snippet(5, "tricky", "see ⟦S1⟧")
        val match = SnippetMatch.Embedded(
            "⟦S1⟧ ⟦S2⟧",
            listOf(SnippetMarker("⟦S1⟧", address), SnippetMarker("⟦S2⟧", tricky)),
        )
        assertEquals("12 High Street\nLondon see ⟦S1⟧", restoreSnippets("⟦S1⟧ ⟦S2⟧", match))
    }

    @Test
    fun `toString hides the transcript`() {
        val match = SnippetMatch.Embedded("secret words", emptyList())
        assertTrue("secret" !in match.toString())
        assertTrue("secret" !in Replaced("secret", emptySet()).toString())
    }

    @TestFactory
    fun `replacements`(): List<DynamicTest> {
        val list = listOf(
            replacement(1, "cooper", "Kubernetes"),
            replacement(2, "get hub", "GitHub"),
            replacement(3, "get", "fetch"),
            replacement(4, "GPT", "GPT"),
            replacement(5, "  ", "blank"),
            replacement(6, "a", "b"),
            replacement(7, "b", "c"),
            replacement(8, "C++", "C plus plus"),
        )
        return listOf(
            Triple("deploy to cooper now", "deploy to Kubernetes now", setOf(1L)),
            Triple("Cooper is down", "Kubernetes is down", setOf(1L)),
            Triple("COOPER is down", "COOPER is down", emptySet()),
            Triple("coopers are", "coopers are", emptySet()),
            Triple("supercooper", "supercooper", emptySet()),
            Triple("cooper2", "cooper2", emptySet()),
            Triple("push to get hub", "push to GitHub", setOf(2L)),
            Triple("get it from get hub", "fetch it from GitHub", setOf(2L, 3L)),
            Triple("Get hub is up", "GitHub is up", setOf(2L)),
            Triple("ask GPT", "ask GPT", emptySet()),
            Triple("a then b", "b then c", setOf(6L, 7L)),
            Triple("cooper, cooper.", "Kubernetes, Kubernetes.", setOf(1L)),
            Triple("learn C++ today", "learn C plus plus today", setOf(8L)),
            Triple("", "", emptySet()),
        ).map { (text, expected, applied) ->
            dynamicTest("'$text'") {
                assertEquals(Replaced(expected, applied.map(::ReplacementId).toSet()), applyReplacements(text, list))
            }
        }
    }

    private fun receipt(text: String, start: Int = 0, via: InsertedVia = InsertedVia.INPUT_CONNECTION): InsertReceipt =
        InsertReceipt(
            target = Fixtures.fieldTarget(),
            text = text,
            replaced = "",
            range = if (via.typed) TextRange(start, start + text.length) else null,
            via = via,
        )

    @TestFactory
    fun `learnable corrections`(): List<DynamicTest> = listOf(
        Triple("Deploy to cooper now.", "Deploy to Kubernetes now.", Correction("cooper", "Kubernetes")),
        Triple("Ask eager about it.", "Ask Igor about it.", Correction("eager", "Igor")),
        Triple("Hello world.", "Hello there.", Correction("world", "there")),
        Triple("Hello world.", "Goodbye world.", Correction("Hello", "Goodbye")),
        Triple("I can't go.", "I won't go.", Correction("can't", "won't")),
        Triple("It's well known.", "It's well-known.", null),
        Triple("Use e mail.", "Use e-mail.", null),
        Triple("Hello world.", "Hello world.", null),
        Triple("Hello world.", "hello world.", null),
        Triple("Hello world.", "Hello big world.", null),
        Triple("Hello big world.", "Hello world.", null),
        Triple("Hello big world.", "Hello small planet.", null),
        Triple("Hello world.", "Hello there", null),
        Triple("Hello world.", "Hello world. And more text typed after.", null),
        Triple("Café ok.", "Cafe ok.", Correction("Café", "Cafe")),
        Triple("Hello world.", "", null),
    ).map { (inserted, edited, expected) ->
        dynamicTest("$inserted -> $edited") { assertEquals(expected, diffCorrection(receipt(inserted), edited)) }
    }

    @Test
    fun `correction compared from the receipt start`() {
        val before = "Dear team, "
        val receipt = receipt("ask eager.", start = before.length)
        assertEquals(Correction("eager", "Igor"), diffCorrection(receipt, before + "ask Igor."))
        assertEquals(Correction("eager", "Igor"), diffCorrection(receipt, before + "ask Igor. PS later"))
        assertEquals(null, diffCorrection(receipt, "short"))
    }

    @Test
    fun `no correction when the text was not typed`() {
        assertEquals(null, diffCorrection(receipt("Hello world.", via = InsertedVia.HANDOFF), "Hello there."))
        assertEquals(null, diffCorrection(receipt("Hello world.", via = InsertedVia.NONE), "Hello there."))
        val noRange = receipt("Hello world.").copy(range = null)
        assertEquals(null, diffCorrection(noRange, "Hello there."))
    }

    @Test
    fun `combining marks belong to the word`() {
        val inserted = "Cafe\u0301 ok."
        assertEquals(Correction("Cafe\u0301", "Bar"), diffCorrection(receipt(inserted), "Bar ok."))
    }
}
