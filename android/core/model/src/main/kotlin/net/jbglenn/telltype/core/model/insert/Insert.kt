package net.jbglenn.telltype.core.model.insert

import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.lengthOnly

/**
 * How the text reached the user; the `history.inserted_via` column (docs/PLAN.md section 7.4 and Appendix E).
 *
 * @property id stable id stored in the database. Never change one.
 */
public enum class InsertedVia(public val id: String) {
    /** Ladder step 1: committed through the accessibility input connection, like a keyboard. */
    INPUT_CONNECTION("input_connection"),

    /** Ladder step 2: the field's text was rebuilt around the cursor and written. */
    SET_TEXT("set_text"),

    /** Ladder step 3: copied, pasted and verified, then the previous clipboard restored. */
    PASTE("paste"),

    /** Ladder step 4: not typed; left on the clipboard (flagged sensitive) and offered as Paste. */
    HANDOFF("handoff"),

    /** Not typed and not on the clipboard ("Never use the clipboard" is on): offered as Copy and kept in History. */
    NONE("none"),
    ;

    /** The text is in the field (steps 1 to 3), so Raw and Undo can act on it. */
    public val typed: Boolean get() = this == INPUT_CONNECTION || this == SET_TEXT || this == PASTE
}

/**
 * Proof of where text went. Returned by the inserter for steps 1 to 3 and built by the domain for hand-offs.
 * Raw and Undo replace [range] in [target] with the raw transcript or with [replaced].
 *
 * @property target the field the text was meant for.
 * @property text the text that was inserted (or handed off).
 * @property replaced the text it replaced: the selection in Command Mode, `""` when inserted at a cursor.
 * @property range where [text] sits in the field now (UTF-16 indices), null when not typed or not known.
 * @property via how it got there.
 */
public data class InsertReceipt(
    public val target: FieldTarget,
    public val text: String,
    public val replaced: String,
    public val range: TextRange?,
    public val via: InsertedVia,
) {
    override fun toString(): String = "InsertReceipt(target=$target, text=${text.lengthOnly()}, " +
        "replaced=${replaced.lengthOnly()}, range=$range, via=$via)"
}
