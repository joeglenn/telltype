package net.jbglenn.telltype.core.model.field

import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.lengthOnly

/**
 * What kind of input a field takes, as far as dictation cares (docs/PLAN.md sections 4.4 and 7.5).
 * `:platform` maps Android input types and flags onto these four.
 */
public enum class FieldKind {
    /** Free text that accepts line breaks: message bodies, notes, documents. */
    MULTI_LINE,

    /** One line of text: search boxes, subject lines, address bars, titles. Gets no line breaks, no final period. */
    SINGLE_LINE,

    /** Any password variation (text, web, visible, numeric). The bubble is never shown. */
    PASSWORD,

    /** Number, decimal, phone, date or time input. The bubble is never shown. */
    NUMERIC,
    ;

    /** Whether the bubble may be offered for a field of this kind. */
    public val acceptsDictation: Boolean get() = this == MULTI_LINE || this == SINGLE_LINE
}

/**
 * The field a dictation is for. Captured when recording starts; the text is typed only into this field
 * (docs/PLAN.md section 6.4: never type into the wrong place).
 *
 * @property handle which field this is; see [FieldHandle] for the identity rules.
 * @property app the package that owns the field's window.
 * @property kind the input kind.
 * @property hint the field's hint or label text ("Message", "Subject"), null when the field has none. Treated as
 *   on-screen context: sent only to the server, never logged.
 * @property incognito the field asked for no personalised learning (IME flag): dictation works, but nothing is
 *   saved to History and no context is read or sent (docs/PLAN.md section 7.5).
 */
public data class FieldTarget(
    public val handle: FieldHandle,
    public val app: AppPackage,
    public val kind: FieldKind,
    public val hint: String?,
    public val incognito: Boolean,
) {
    override fun toString(): String =
        "FieldTarget(handle=$handle, app=${app.name}, kind=$kind, hint=${hint?.lengthOnly()}, incognito=$incognito)"
}

/**
 * A range of characters in a field, as UTF-16 indices (the unit Android uses for selections).
 * An empty range is a cursor position.
 *
 * @property start first index, inclusive; `0 <= start <= end`.
 * @property end last index, exclusive.
 */
public data class TextRange(public val start: Int, public val end: Int) {
    init {
        require(start in 0..end) { "a text range needs 0 <= start <= end, got $start..$end" }
    }

    /** True for a plain cursor (nothing selected). */
    public val isEmpty: Boolean get() = start == end

    /** Number of UTF-16 characters covered. */
    public val length: Int get() = end - start
}

/**
 * A rectangle on the screen in physical pixels, screen coordinates (origin top-left).
 *
 * @property left left edge, inclusive.
 * @property top top edge, inclusive.
 * @property right right edge, exclusive; `right >= left`.
 * @property bottom bottom edge, exclusive; `bottom >= top`.
 */
public data class ScreenRect(public val left: Int, public val top: Int, public val right: Int, public val bottom: Int) {
    init {
        require(right >= left && bottom >= top) { "a rectangle needs right >= left and bottom >= top" }
    }
}

/**
 * What `:platform` currently sees of the focused editable field: enough to decide whether to show the bubble,
 * in which mode, and where to place it. Produced on every relevant accessibility event (debounced and
 * de-duplicated).
 *
 * @property target the focused field.
 * @property fieldBounds where the field is on screen.
 * @property keyboardBounds where the keyboard (input-method window) is, or null when no keyboard is shown.
 * @property selection the field's current selection or cursor, null when the field does not report one.
 */
public data class FieldSnapshot(
    public val target: FieldTarget,
    public val fieldBounds: ScreenRect,
    public val keyboardBounds: ScreenRect?,
    public val selection: TextRange?,
) {
    /** The keyboard is up. The bubble is shown only while it is. */
    public val keyboardVisible: Boolean get() = keyboardBounds != null

    /** Text is selected, so a dictation becomes a Command Mode instruction (docs/PLAN.md section 4.7). */
    public val hasSelection: Boolean get() = selection?.isEmpty == false
}

/**
 * The text around the cursor, read once when recording starts (docs/PLAN.md section 4.3). Held in memory for one
 * dictation, sent only to the server, never stored or logged. Empty for incognito fields and when nothing is readable.
 *
 * @property textBefore up to the configured number of characters before the cursor (or before the selection).
 * @property textAfter up to the configured number of characters after the cursor (or after the selection).
 * @property selectedText the selected text, `""` when nothing is selected. Command Mode rewrites exactly this.
 */
public data class FieldContext(
    public val textBefore: String,
    public val textAfter: String,
    public val selectedText: String,
) {
    override fun toString(): String = "FieldContext(textBefore=${textBefore.lengthOnly()}, " +
        "textAfter=${textAfter.lengthOnly()}, selectedText=${selectedText.lengthOnly()})"

    public companion object {
        /** Nothing readable, or reading not allowed (incognito field, context setting off). */
        public val Empty: FieldContext = FieldContext(textBefore = "", textAfter = "", selectedText = "")
    }
}
