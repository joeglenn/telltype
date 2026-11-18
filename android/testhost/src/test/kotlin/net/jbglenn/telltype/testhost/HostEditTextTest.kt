package net.jbglenn.telltype.testhost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Every way text reaches the host's field from outside, against the deaf and the paste-only modes. */
@RunWith(AndroidJUnit4::class)
class HostEditTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun field(configure: HostEditText.() -> Unit = {}) = HostEditText(context, null).apply {
        configure()
        isFocusableInTouchMode = true
        requestFocus()
    }

    private fun HostEditText.connection(): InputConnection = requireNotNull(onCreateInputConnection(EditorInfo()))

    private fun HostEditText.setTextAction(value: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clip(value: String) = context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("test", value))

    @Test
    fun `a length limit on top of the host's own filter keeps the first characters`() {
        val field = field { filters += android.text.InputFilter.LengthFilter(10) }

        field.connection().commitText("one two three four", 1, null)

        assertEquals("one two th", field.text.toString())
    }

    @Test
    fun `a plain field takes text every way`() {
        val field = field()

        field.connection().commitText("one", 1, null)
        field.connection().commitText(" two", 1)
        clip(" three")
        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("one two three", field.text.toString())
    }

    @Test
    fun `a deaf field keeps nothing from the input connection, set-text or paste`() {
        val field = field { deaf = true }

        field.connection().commitText("typed with an attribute", 1, null)
        field.connection().commitText("typed", 1)
        field.connection().setComposingText("composing", 1)
        field.connection().finishComposingText()
        field.setTextAction("set")
        clip("pasted")
        field.onTextContextMenuItem(android.R.id.paste)
        field.connection().performContextMenuAction(android.R.id.paste)

        assertEquals("", field.text.toString())
    }

    @Test
    fun `a paste-only field keeps pasted text and nothing else`() {
        val field = field { pasteOnly = true }

        field.connection().commitText("typed", 1, null)
        field.setTextAction("set")
        clip("pasted")
        field.onTextContextMenuItem(android.R.id.paste)

        assertEquals("pasted", field.text.toString())
    }
}
