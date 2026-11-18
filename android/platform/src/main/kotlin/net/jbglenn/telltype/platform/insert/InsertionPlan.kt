package net.jbglenn.telltype.platform.insert

import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.field.FieldState

/**
 * How an insertion is recognised when the field is read back, chosen from what the field exposes before it.
 * Holds on-screen text: [toString] prints lengths only.
 */
internal sealed interface Expectation {
    /** Checks [state] (read after an attempt) and returns the confirmed insertion's place, or null. */
    fun match(state: FieldState, text: String): Splice.Located?

    /**
     * Checks [state] for [text] as the field adapted it on the way in (an auto-capitalising or filtering field) and
     * returns where the adapted text sits, or null. Only an expectation that knows the surrounding text can tell.
     */
    fun adapted(state: FieldState, text: String): Splice.Located? = null

    /** Readable text and a known selection: the whole new text is known exactly. */
    data class FullText(val value: String, val inserted: TextRange, val replaced: String) : Expectation {
        override fun match(state: FieldState, text: String): Splice.Located? =
            if (state.content == value) Splice.Located(inserted, replaced) else null

        /**
         * The text around the insertion is unchanged and what sits in between is a non-empty adaptation of [text]:
         * the same letters ignoring case, possibly with some left out ([Splice.isAdaptation]). The [replaced] text
         * still in place is no adaptation: nothing landed, even when it differs from [text] only in case (a Command
         * Mode rewrite to capitals).
         */
        override fun adapted(state: FieldState, text: String): Splice.Located? {
            val after = state.content ?: return null
            val prefix = value.substring(0, inserted.start)
            val suffix = value.substring(inserted.end)
            val end = after.length - suffix.length
            val fits = end > prefix.length && after.startsWith(prefix) && after.endsWith(suffix)
            val between = if (fits) after.substring(prefix.length, end) else null
            return Splice.Located(TextRange(prefix.length, end), replaced)
                .takeIf { between != null && between != replaced && Splice.isAdaptation(between, text) }
        }

        override fun toString(): String = "FullText(${value.length} chars, inserted=$inserted)"
    }

    /** Unreadable text but a known selection: the cursor must land right after the inserted text. */
    data class Cursor(val inserted: TextRange, val replaced: String) : Expectation {
        override fun match(state: FieldState, text: String): Splice.Located? =
            if (state.selection == TextRange(inserted.end, inserted.end)) Splice.Located(inserted, replaced) else null

        override fun toString(): String = "Cursor(inserted=$inserted)"
    }

    /** Readable text without a selection: the new text must be the old one with one range replaced by ours. */
    data class Located(val before: String) : Expectation {
        override fun match(state: FieldState, text: String): Splice.Located? =
            state.content?.let { after -> Splice.locate(before, after, text) }

        override fun toString(): String = "Located(${before.length} chars)"
    }
}

/**
 * One insertion, prepared from the field's state before the first attempt.
 *
 * @property initial the field before any attempt.
 * @property text what to insert.
 * @property range where it goes, null when the field reports no selection.
 * @property expectation how success is recognised.
 * @property allowClipboard step 3 may run.
 */
internal data class InsertionPlan(
    val initial: FieldState,
    val text: String,
    val range: TextRange?,
    val expectation: Expectation,
    val allowClipboard: Boolean,
) {
    override fun toString(): String =
        "InsertionPlan(initial=$initial, text=${text.length} chars, range=$range, expectation=$expectation)"

    companion object {
        /** The plan for [request] on a field in [initial] state, or null when no attempt could be verified. */
        fun of(initial: FieldState, request: InsertRequest): InsertionPlan? {
            val content = initial.content
            val range = request.range ?: initial.contentSelection ?: initial.selection.takeIf { content == null }
            val expectation = when {
                content != null && range != null -> {
                    val inserted = TextRange(range.start, range.start + request.text.length)
                    Expectation.FullText(Splice.apply(content, range, request.text), inserted, content.slice(range))
                }

                range != null -> Expectation.Cursor(
                    TextRange(range.start, range.start + request.text.length),
                    request.expected.orEmpty(),
                )

                content != null -> Expectation.Located(content)

                else -> null
            }
            return expectation?.let { InsertionPlan(initial, request.text, range, it, request.allowClipboard) }
        }

        private fun String.slice(range: TextRange): String = substring(range.start, range.end)
    }
}
