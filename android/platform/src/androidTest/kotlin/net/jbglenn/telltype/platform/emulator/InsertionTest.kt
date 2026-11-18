package net.jbglenn.telltype.platform.emulator

import kotlinx.coroutines.runBlocking
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.platform.emulator.InsertSteps.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The full insertion ladder on every field type that takes dictation: at the cursor, in the middle of the text, over
 * a selection, then Raw and Undo through `replace`. Each receipt names the step that landed, and the field's text is
 * checked independently through UI Automator.
 */
@RunWith(Parameterized::class)
class InsertionTest(private val type: HostField) {
    @Before
    fun connect() {
        Emulator.connectService()
    }

    @Test
    fun insertsAtTheCursor() {
        val (initial, cursor) = when (type) {
            HostField.PLACEHOLDER -> "" to 0
            HostField.PREFILLED -> PREFILLED to PREFILLED.length
            else -> "Hello" to 5
        }
        val snapshot = InsertSteps.prepare(type, initial, TextRange(cursor, cursor))

        val result = InsertSteps.insert("at-cursor", type, snapshot, " world")

        val receipt = (result as Outcome.Ok).value
        assertEquals("$initial world", InsertSteps.fieldText(type))
        assertEquals(TextRange(cursor, cursor + 6), receipt.range)
        assertTrue(receipt.via.typed)
    }

    @Test
    fun insertsInTheMiddle() {
        val initial = if (type == HostField.PREFILLED) PREFILLED else "Hello world"
        if (type == HostField.PLACEHOLDER) return placeholderHasNoMiddle()
        val snapshot = InsertSteps.prepare(type, initial, TextRange(5, 5))

        val result = InsertSteps.insert("mid-text", type, snapshot, ",")

        assertTrue(result.describe(), result is Outcome.Ok)
        assertEquals(initial.substring(0, 5) + "," + initial.substring(5), InsertSteps.fieldText(type))
    }

    @Test
    fun replacesTheSelection() {
        val (initial, selection, expected) = when (type) {
            HostField.PREFILLED -> Triple(PREFILLED, null, "The slow brown fox")
            HostField.PLACEHOLDER -> return placeholderHasNoMiddle()
            else -> Triple("Hello world", TextRange(6, 11), "Hello slow")
        }
        val snapshot = InsertSteps.prepare(type, initial, selection)
        assertEquals(selection ?: TextRange(4, 9), snapshot.selection)

        val result = InsertSteps.insert("replace-selection", type, snapshot, "slow")

        val receipt = (result as Outcome.Ok).value
        assertEquals(expected, InsertSteps.fieldText(type))
        assertEquals(if (type == HostField.PREFILLED) "quick" else "world", receipt.replaced)
    }

    @Test
    fun rawAndUndoReplaceOnlyTheInsertion() {
        val initial = if (type == HostField.PREFILLED) PREFILLED else "Hello"
        val start = if (type == HostField.PLACEHOLDER) "" else initial
        val snapshot = InsertSteps.prepare(type, start, TextRange(start.length, start.length))
        val first = (InsertSteps.insert("raw-undo", type, snapshot, " cleaned") as Outcome.Ok).value

        val raw = replace(first, " um raw")
        assertEquals("$start um raw", InsertSteps.fieldText(type))
        Emulator.record("raw", type.id, "ladder", raw.via.id)
        val undo = replace(raw, "")
        assertEquals(start, InsertSteps.fieldText(type))
        Emulator.record("undo", type.id, "ladder", undo.via.id)
    }

    private fun replace(receipt: InsertReceipt, text: String): InsertReceipt {
        val result = runBlocking { Emulator.graph.textInserter.replace(receipt, text) }
        assertTrue(result.describe(), result is Outcome.Ok)
        return (result as Outcome.Ok).value
    }

    /** The placeholder screen starts empty: there is no middle and no selection to test. */
    private fun placeholderHasNoMiddle() {
        Emulator.record("mid-or-selection", type.id, "-", "n/a (empty field)")
    }

    private companion object {
        const val PREFILLED = "The quick brown fox"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun types(): List<Array<Any>> = HostField.dictatable.map { arrayOf(it) }
    }
}
