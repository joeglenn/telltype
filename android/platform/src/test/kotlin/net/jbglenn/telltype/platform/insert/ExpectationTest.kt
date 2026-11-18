package net.jbglenn.telltype.platform.insert

import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.field.FieldState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Package V1: a Command Mode rewrite that differs from the selection only in case ("please call me back" to
 * "PLEASE CALL ME BACK") must not count as typed while the field still holds the selection (seen on the emulator:
 * the receipt said typed, the field was unchanged, no Paste was offered).
 */
class ExpectationTest {
    private val original = "please call me back"
    private val rewrite = "PLEASE CALL ME BACK"
    private val expectation = Expectation.FullText(rewrite, TextRange(0, rewrite.length), replaced = original)

    private fun field(text: String) = FieldState(
        text,
        TextRange(text.length, text.length),
        showingHint = false,
        kind = FieldKind.MULTI_LINE,
        focused = true,
    )

    @Test
    fun `the selection still in place is not an adaptation of a rewrite that only changes case`() {
        assertNull(expectation.adapted(field(original), rewrite))
    }

    @Test
    fun `a field that lowered the case of the rewrite it took still counts as typed`() {
        val lowered = Expectation.FullText(rewrite, TextRange(0, rewrite.length), replaced = "call me")
        assertEquals(TextRange(0, original.length), lowered.adapted(field(original), rewrite)?.inserted)
    }
}
