package net.jbglenn.telltype.testhost

import android.app.Activity
import android.os.Bundle
import android.widget.EditText

/**
 * A chat app's screen ([FieldType.CHAT]): the message field (`field`) at the bottom of the screen with a send button
 * (`send`) beside it, outside the field, as in Telegram, whose microphone button the bubble covered (board B2).
 * The field is focused at start, so the keyboard comes up and the row sits right above it.
 */
class ChatFieldActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_field)
        val field = findViewById<EditText>(R.id.field)
        intent.getStringExtra(HostExtras.INITIAL_TEXT)?.let(field::setText)
        field.requestFocus()
    }
}
