package net.jbglenn.telltype.platform.context

import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.platform.field.FieldState

/**
 * Cuts the text around the cursor out of a [FieldState] (docs/PLAN.md section 4.3). Pure. Never reads a field
 * that does not take dictation (password, numeric) and never splits a surrogate pair at a cut.
 */
internal object ContextSlicer {
    /** Up to [charsBefore] characters before the selection and [charsAfter] after it, plus the selection. */
    fun slice(state: FieldState, charsBefore: Int, charsAfter: Int): FieldContext {
        val text = state.content
        val selection = state.contentSelection
        if (!state.kind.acceptsDictation || text == null || selection == null) return FieldContext.Empty
        val beforeStart = (selection.start - charsBefore.coerceAtLeast(0)).coerceAtLeast(0)
        val afterEnd = (selection.end + charsAfter.coerceAtLeast(0)).coerceAtMost(text.length)
        return FieldContext(
            textBefore = text.substring(beforeStart, selection.start).dropLeadingLowSurrogate(),
            textAfter = text.substring(selection.end, afterEnd).dropTrailingHighSurrogate(),
            selectedText = text.substring(selection.start, selection.end),
        )
    }

    private fun String.dropLeadingLowSurrogate(): String =
        if (isNotEmpty() && first().isLowSurrogate()) substring(1) else this

    private fun String.dropTrailingHighSurrogate(): String =
        if (isNotEmpty() && last().isHighSurrogate()) dropLast(1) else this
}
