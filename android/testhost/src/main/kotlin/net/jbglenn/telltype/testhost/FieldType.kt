package net.jbglenn.telltype.testhost

import android.content.Intent

/** The field types the host offers (docs/PLAN.md section 14.2), one screen each. */
enum class FieldType(
    /** The value of the [HostExtras.FIELD_TYPE] extra. */
    val id: String,
    /** The activity that shows it. */
    val screen: Class<*>,
) {
    /** A plain single-line `EditText`. */
    PLAIN("plain", ViewFieldActivity::class.java),

    /** A multi-line `EditText`. */
    MULTI_LINE("multi_line", ViewFieldActivity::class.java),

    /** A search box: single line, search action, hint "Search". */
    SEARCH("search", ViewFieldActivity::class.java),

    /** A text password. */
    PASSWORD("password", ViewFieldActivity::class.java),

    /** A number field. */
    NUMBER("number", ViewFieldActivity::class.java),

    /** A phone number field. */
    PHONE("phone", ViewFieldActivity::class.java),

    /** An empty field showing placeholder (hint) text. */
    PLACEHOLDER("placeholder", ViewFieldActivity::class.java),

    /** A field with a pre-filled value and a selection ("quick" in "The quick brown fox"). */
    PREFILLED("prefilled", ViewFieldActivity::class.java),

    /** A field that asks for no personalised learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`). */
    INCOGNITO("incognito", ViewFieldActivity::class.java),

    /** A plain field that takes the focus but ignores all text from outside ([HostEditText.deaf]). */
    DEAF("deaf", ViewFieldActivity::class.java),

    /** A plain field that keeps text only when it is pasted ([HostEditText.pasteOnly]). */
    PASTE_ONLY("paste_only", ViewFieldActivity::class.java),

    /** A field that adapts what it takes: everything in capitals, commas left out (input filters). */
    ADAPTING("adapting", ViewFieldActivity::class.java),

    /** A field that keeps at most 40 characters (`InputFilter.LengthFilter`): a long dictation is cut short. */
    LENGTH_LIMITED("length_limited", ViewFieldActivity::class.java),

    /** A chat app's message field at the bottom of the screen with a send button beside it (board B2). */
    CHAT("chat", ChatFieldActivity::class.java),

    /** A Compose Material 3 `TextField`. */
    COMPOSE("compose", ComposeFieldActivity::class.java),

    /** An `<input type="text">` in a local WebView page. */
    WEB_INPUT("web_input", WebFieldActivity::class.java),

    /** A `<textarea>` in a local WebView page. */
    WEB_TEXTAREA("web_textarea", WebFieldActivity::class.java),
    ;

    companion object {
        /** The type named by [intent]'s extra; [PLAIN] when the extra is missing or unknown. */
        fun of(intent: Intent): FieldType {
            val id = intent.getStringExtra(HostExtras.FIELD_TYPE)
            return entries.firstOrNull { it.id == id } ?: PLAIN
        }
    }
}

/** Intent extras every field screen understands. */
object HostExtras {
    /** Which [FieldType] to show (string id). */
    const val FIELD_TYPE: String = "field_type"

    /** Text to put in the field at start (string), instead of the type's default. */
    const val INITIAL_TEXT: String = "initial_text"

    /** Selection start at start (int); used with [SELECTION_END]. View and Compose screens only. */
    const val SELECTION_START: String = "selection_start"

    /** Selection end at start (int). */
    const val SELECTION_END: String = "selection_end"
}
