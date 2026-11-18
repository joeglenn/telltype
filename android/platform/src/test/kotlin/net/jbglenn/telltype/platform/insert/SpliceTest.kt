package net.jbglenn.telltype.platform.insert

import net.jbglenn.telltype.core.model.field.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpliceTest {
    @Test
    fun `apply inserts at a cursor and replaces a selection`() {
        assertEquals("Hello, world", Splice.apply("Hello world", TextRange(5, 5), ","))
        assertEquals("Hello Oslo", Splice.apply("Hello world", TextRange(6, 11), "Oslo"))
        assertEquals("", Splice.apply("abc", TextRange(0, 3), ""))
    }

    @Test
    fun `locate finds an insertion at a cursor`() {
        assertEquals(
            Splice.Located(TextRange(5, 6), ""),
            Splice.locate("Hello world", "Hello, world", ","),
        )
    }

    @Test
    fun `locate finds a replaced selection and what it replaced`() {
        assertEquals(
            Splice.Located(TextRange(6, 10), "world"),
            Splice.locate("Hello world", "Hello Oslo", "Oslo"),
        )
    }

    @Test
    fun `locate gives the first explanation when the text repeats`() {
        assertEquals(Splice.Located(TextRange(0, 1), ""), Splice.locate("aa", "aaa", "a"))
    }

    @Test
    fun `an adaptation changes case and leaves characters out, nothing else`() {
        assertEquals(true, Splice.isAdaptation("HELLO THERE", "Hello, there"))
        assertEquals(true, Splice.isAdaptation("hello there", "Hello there"))
        assertEquals(false, Splice.isAdaptation("Hello there!", "Hello there"))
        assertEquals(false, Splice.isAdaptation("there hello", "hello there"))
        assertEquals(false, Splice.isAdaptation(" ", "hello there"))
        assertEquals(false, Splice.isAdaptation("", "hello"))
    }

    @Test
    fun `a field that keeps only part of the dictation has not typed it`() {
        val dictated = "Please send the report to Maria by Friday, and copy the whole team on it."
        assertEquals("all capitals: typed", true, Splice.isAdaptation(dictated.uppercase(), dictated))
        assertEquals("commas filtered: typed", true, Splice.isAdaptation(dictated.replace(",", ""), dictated))
        assertEquals(
            "every punctuation mark and space filtered: typed",
            true,
            Splice.isAdaptation(dictated.filter(Char::isLetterOrDigit), dictated),
        )
        assertEquals(
            "truncated to a third: hand-off",
            false,
            Splice.isAdaptation(dictated.take(dictated.length / 3), dictated),
        )
        assertEquals("first ten characters: hand-off", false, Splice.isAdaptation(dictated.take(10), dictated))
        assertEquals("the trailing full stop cut: typed", true, Splice.isAdaptation(dictated.dropLast(1), dictated))
        assertEquals("the trailing letter cut: hand-off", false, Splice.isAdaptation(dictated.dropLast(2), dictated))
        assertEquals(
            "one letter in the middle filtered: typed (above 90 %)",
            true,
            Splice.isAdaptation(dictated.replaceFirst("report", "rport"), dictated),
        )
        assertEquals(
            "every vowel filtered: hand-off (below 90 %)",
            false,
            Splice.isAdaptation(dictated.filterNot { it in "aeiou" }, dictated),
        )
        assertEquals("characters added: hand-off", false, Splice.isAdaptation("$dictated!", dictated))
        assertEquals(
            "a word added: hand-off",
            false,
            Splice.isAdaptation(dictated.replace("Maria", "Mariah"), dictated),
        )
    }

    @Test
    fun `locate rejects texts that are not one replacement`() {
        assertNull(Splice.locate("Hello world", "Hello world", "!"))
        assertNull(Splice.locate("Hello world", "Goodbye world!", "!"))
        assertNull(Splice.locate("abc", "a", "zz"))
    }

    @Test
    fun `located text prints no content`() {
        val located = Splice.Located(TextRange(0, 1), "words")

        assertEquals("Located(inserted=TextRange(start=0, end=1), replaced=5 chars)", located.toString())
    }
}
