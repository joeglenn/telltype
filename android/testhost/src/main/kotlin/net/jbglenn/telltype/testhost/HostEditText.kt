package net.jbglenn.telltype.testhost

import android.content.Context
import android.text.InputFilter
import android.util.AttributeSet
import android.widget.EditText

/**
 * The host's field under test: an ordinary `EditText`, or one that refuses text from outside.
 *
 * - [deaf]: takes the focus and the keyboard but keeps none of the text put in from outside: committed and
 *   composing text on its input connection (both `commitText` variants, `replaceText`), the accessibility set-text
 *   and paste actions, and paste from the menu. The app's typing ladder then never sees its text arrive ("typing
 *   not confirmed", docs/PLAN.md section 10) and has to hand the text off.
 * - [pasteOnly]: keeps text only when it is pasted (the menu, the input connection's or the accessibility paste).
 *
 * Both are enforced by one [InputFilter], which every way into an `Editable` and `setText` pass through. Wrapping
 * the input connection is not enough: the accessibility input connection commits through
 * `commitText(text, cursor, TextAttribute)`, which `InputConnectionWrapper` forwards past an overridden two-argument
 * `commitText` (the A7g finding: the deaf field took the dictation through that call).
 */
class HostEditText(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {
    /** Keep no text from outside (see the class comment). */
    var deaf: Boolean = false

    /** Keep text only when it is pasted. */
    var pasteOnly: Boolean = false

    private var pasting = false

    init {
        filters = arrayOf(InputFilter { source, start, end, _, _, _ -> "".takeIf { end > start && refuses() } })
    }

    override fun onTextContextMenuItem(id: Int): Boolean {
        pasting = id in PASTE_ITEMS
        return try {
            super.onTextContextMenuItem(id)
        } finally {
            pasting = false
        }
    }

    private fun refuses(): Boolean = deaf || (pasteOnly && !pasting)

    private companion object {
        val PASTE_ITEMS = setOf(android.R.id.paste, android.R.id.pasteAsPlainText)
    }
}
