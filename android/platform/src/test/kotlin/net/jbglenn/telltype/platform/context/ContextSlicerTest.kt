package net.jbglenn.telltype.platform.context

import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.field.FieldState
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextSlicerTest {
    private fun state(
        text: String?,
        selection: TextRange?,
        kind: FieldKind = FieldKind.MULTI_LINE,
        hint: Boolean = false,
    ) = FieldState(text, selection, hint, kind, focused = true)

    @Test
    fun `slices before and after the cursor within the limits`() {
        val context = ContextSlicer.slice(state("Hello there, world", TextRange(6, 6)), charsBefore = 3, charsAfter = 5)

        assertEquals(FieldContext(textBefore = "lo ", textAfter = "there", selectedText = ""), context)
    }

    @Test
    fun `keeps the whole selection, whatever the limits`() {
        val context =
            ContextSlicer.slice(state("Hello there, world", TextRange(6, 11)), charsBefore = 100, charsAfter = 0)

        assertEquals(FieldContext(textBefore = "Hello ", textAfter = "", selectedText = "there"), context)
    }

    @Test
    fun `negative limits read nothing around the cursor`() {
        val context = ContextSlicer.slice(state("Hello", TextRange(2, 2)), charsBefore = -1, charsAfter = -5)

        assertEquals(FieldContext.Empty, context)
    }

    @Test
    fun `never cuts a surrogate pair in half`() {
        val text = "a\uD83D\uDE00b\uD83D\uDE00c"
        val context = ContextSlicer.slice(state(text, TextRange(4, 4)), charsBefore = 2, charsAfter = 2)

        assertEquals("b", context.textBefore)
        assertEquals("\uD83D\uDE00", context.textAfter)
    }

    @Test
    fun `password, numeric, placeholder and unreadable fields give nothing`() {
        listOf(
            state("hunter2", TextRange(7, 7), kind = FieldKind.PASSWORD),
            state("1234", TextRange(4, 4), kind = FieldKind.NUMERIC),
            state("Type a message", TextRange(0, 0), hint = true),
            state(null, TextRange(0, 0)),
            state("Hello", selection = null),
        ).forEach { assertEquals(it.toString(), FieldContext.Empty, ContextSlicer.slice(it, 10, 10)) }
    }

    @Test
    fun `a field state prints no content`() {
        assertEquals(
            "FieldState(text=7 chars, selection=null, showingHint=false, kind=MULTI_LINE, focused=true)",
            state("private", null).toString(),
        )
    }
}
