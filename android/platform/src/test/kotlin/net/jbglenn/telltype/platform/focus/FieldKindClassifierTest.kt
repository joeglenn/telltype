package net.jbglenn.telltype.platform.focus

import android.text.InputType
import android.view.inputmethod.EditorInfo
import net.jbglenn.telltype.core.model.field.FieldKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldKindClassifierTest {
    private val text = InputType.TYPE_CLASS_TEXT

    private fun kind(inputType: Int, isPassword: Boolean = false, isMultiLine: Boolean = false) =
        FieldKindClassifier.classify(inputType, isPassword, isMultiLine)

    @Test
    fun `every password variation is a password`() {
        val variations = listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        variations.forEach { assertEquals("input type $it", FieldKind.PASSWORD, kind(it)) }
    }

    @Test
    fun `the node's password flag wins over anything else`() {
        assertEquals(FieldKind.PASSWORD, kind(InputType.TYPE_NULL, isPassword = true))
        assertEquals(FieldKind.PASSWORD, kind(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, isPassword = true))
    }

    @Test
    fun `number, decimal, phone, date and time are numeric`() {
        val numeric = listOf(
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            InputType.TYPE_CLASS_PHONE,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
        )
        numeric.forEach { assertEquals("input type $it", FieldKind.NUMERIC, kind(it)) }
    }

    @Test
    fun `multi-line comes from the flags or the node`() {
        assertEquals(FieldKind.MULTI_LINE, kind(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertEquals(FieldKind.MULTI_LINE, kind(text or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE))
        assertEquals(FieldKind.MULTI_LINE, kind(InputType.TYPE_NULL, isMultiLine = true))
    }

    @Test
    fun `other text, email, uri and no input type are single line`() {
        listOf(
            text,
            text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            text or InputType.TYPE_TEXT_VARIATION_URI,
            text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            InputType.TYPE_NULL,
        ).forEach { assertEquals("input type $it", FieldKind.SINGLE_LINE, kind(it)) }
    }

    @Test
    fun `incognito is the no-personalised-learning flag`() {
        assertTrue(FieldKindClassifier.isIncognito(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(
            FieldKindClassifier.isIncognito(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEND),
        )
        assertFalse(FieldKindClassifier.isIncognito(EditorInfo.IME_ACTION_SEARCH))
    }
}
