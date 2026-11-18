package net.jbglenn.telltype.platform.emulator

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText

/** A screen of the test APK itself with one plain field (`own_field`), focused at start. */
class OwnFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_TEXT
        field.contentDescription = OWN_FIELD
        field.setText(intent.getStringExtra(EXTRA_TEXT).orEmpty())
        setContentView(field)
        field.requestFocus()
    }

    companion object {
        const val OWN_FIELD = "own_field"
        const val EXTRA_TEXT = "text"
    }
}
