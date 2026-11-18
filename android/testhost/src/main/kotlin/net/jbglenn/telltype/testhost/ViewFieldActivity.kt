package net.jbglenn.telltype.testhost

import android.app.Activity
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * The screen for every View-based field type: the field under test (`field`) is configured from the
 * [HostExtras.FIELD_TYPE] extra and focused at start, so the keyboard comes up with it.
 */
class ViewFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_view_field)
        bindHostControls()
        val field = findViewById<EditText>(R.id.field)
        configure(field, FieldType.of(intent))
        intent.getStringExtra(HostExtras.INITIAL_TEXT)?.let(field::setText)
        requestedSelection()?.let { field.setSelection(it.first, it.last) }
        field.requestFocus()
    }

    private fun configure(field: EditText, type: FieldType) {
        when (type) {
            FieldType.MULTI_LINE -> {
                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                field.minLines = MULTI_LINE_ROWS
            }

            FieldType.SEARCH -> {
                field.inputType = InputType.TYPE_CLASS_TEXT
                field.imeOptions = EditorInfo.IME_ACTION_SEARCH
                field.setHint(R.string.search_hint)
            }

            FieldType.PASSWORD ->
                field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

            FieldType.NUMBER -> field.inputType = InputType.TYPE_CLASS_NUMBER

            FieldType.PHONE -> field.inputType = InputType.TYPE_CLASS_PHONE

            FieldType.PLACEHOLDER -> field.setHint(R.string.placeholder_hint)

            FieldType.PREFILLED -> {
                field.setText(R.string.prefilled_text)
                field.setSelection(PREFILLED_SELECTION.first, PREFILLED_SELECTION.last)
            }

            FieldType.INCOGNITO -> field.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

            FieldType.DEAF -> (field as HostEditText).deaf = true

            FieldType.PASTE_ONLY -> (field as HostEditText).pasteOnly = true

            FieldType.ADAPTING -> field.filters += arrayOf(InputFilter.AllCaps(), WITHOUT_COMMAS)

            FieldType.LENGTH_LIMITED -> field.filters += InputFilter.LengthFilter(LENGTH_LIMIT)

            else -> field.inputType = InputType.TYPE_CLASS_TEXT
        }
    }

    private companion object {
        const val MULTI_LINE_ROWS = 3

        /** The characters a [FieldType.LENGTH_LIMITED] field keeps. */
        const val LENGTH_LIMIT = 40

        /** Leaves commas out of whatever the field takes. */
        val WITHOUT_COMMAS = InputFilter { source, start, end, _, _, _ ->
            source.subSequence(start, end).toString().replace(",", "").takeIf { it.length != end - start }
        }

        /** "quick" in "The quick brown fox". */
        val PREFILLED_SELECTION = 4..9
    }
}
