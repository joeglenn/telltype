package net.jbglenn.telltype.platform.field

import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange

/**
 * One reading of a field, taken on the accessibility thread. Holds on-screen text: [toString] prints lengths only.
 *
 * @property text the field's text as reported, null when the field does not expose it. While [showingHint] is
 *   true this is the hint, not content.
 * @property selection the selection or cursor, null when the field reports none.
 * @property showingHint the field is empty and shows its placeholder text.
 * @property kind the field's kind now.
 * @property focused the field has input focus in the focused window, so an input connection serves it.
 */
internal data class FieldState(
    val text: String?,
    val selection: TextRange?,
    val showingHint: Boolean,
    val kind: FieldKind,
    val focused: Boolean,
) {
    /** The field's content: empty while the placeholder shows, null when unreadable. */
    val content: String? get() = if (showingHint) "" else text

    /**
     * The selection within [content]; null when either is unknown or the selection lies outside the content. An empty
     * field (or one showing its placeholder) has its cursor at 0 even when it reports no selection, as an empty
     * `EditText` does.
     */
    val contentSelection: TextRange?
        get() {
            val value = content ?: return null
            return selection?.takeIf { it.end <= value.length } ?: TextRange(0, 0).takeIf { value.isEmpty() }
        }

    override fun toString(): String = "FieldState(text=${text?.length} chars, selection=$selection, " +
        "showingHint=$showingHint, kind=$kind, focused=$focused)"
}

/**
 * The operations the insertion ladder, the context reader and the edit watcher need on one resolved field.
 * Implemented over an `AccessibilityNodeInfo` (blocking cross-process calls, each bounded by the framework's own
 * interaction timeout: call only on the accessibility thread) and by a fake in JVM tests. No method throws;
 * failures are `null` or `false`.
 */
internal interface FieldAccess {
    /** The id of the window the field is in, to match text-changed events. */
    val windowId: Int

    /** Re-reads the field; null when it no longer exists. */
    fun read(): FieldState?

    /**
     * Ladder step 1: through the accessibility input connection, selects [range] (when known) and commits [text]
     * there, as a keyboard would. False when no input connection serves this field, so nothing was sent.
     */
    fun commitText(range: TextRange?, text: String): Boolean

    /** Ladder step 2: replaces the whole text with [text] and puts the cursor at [cursor]. False when refused. */
    fun setText(text: String, cursor: Int): Boolean

    /** Ladder step 3: selects [range] (when known) and pastes the clipboard there. False when refused. */
    fun paste(range: TextRange?): Boolean
}
