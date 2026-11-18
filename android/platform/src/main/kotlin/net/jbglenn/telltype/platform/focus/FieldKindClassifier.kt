package net.jbglenn.telltype.platform.focus

import android.text.InputType
import android.view.inputmethod.EditorInfo
import net.jbglenn.telltype.core.model.field.FieldKind

/**
 * Maps what Android says about an editable field onto a [FieldKind] (docs/PLAN.md section 7.5). Fails safe: when
 * any source says password, the field is a password; numeric, phone, date and time classes are numeric.
 */
internal object FieldKindClassifier {
    /**
     * @param inputType the node's input type, or the editor's when the node reports none (`TYPE_NULL`).
     * @param isPassword the node's own password flag (set by every password variation, also in web pages).
     * @param isMultiLine the node's multi-line flag.
     */
    fun classify(inputType: Int, isPassword: Boolean, isMultiLine: Boolean): FieldKind {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val textClass = inputClass == InputType.TYPE_CLASS_TEXT
        return when {
            isPassword || isPasswordVariation(inputClass, variation) -> FieldKind.PASSWORD
            inputClass in NUMERIC_CLASSES -> FieldKind.NUMERIC
            isMultiLine || (textClass && hasMultiLineFlag(inputType)) -> FieldKind.MULTI_LINE
            else -> FieldKind.SINGLE_LINE
        }
    }

    /** Whether the editor asked for no personalised learning (an incognito field). */
    fun isIncognito(imeOptions: Int): Boolean = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0

    private fun isPasswordVariation(inputClass: Int, variation: Int): Boolean = when (inputClass) {
        InputType.TYPE_CLASS_TEXT -> variation in TEXT_PASSWORD_VARIATIONS
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }

    private fun hasMultiLineFlag(inputType: Int): Boolean =
        inputType and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0

    private val TEXT_PASSWORD_VARIATIONS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
    )

    private val NUMERIC_CLASSES = setOf(
        InputType.TYPE_CLASS_NUMBER,
        InputType.TYPE_CLASS_PHONE,
        InputType.TYPE_CLASS_DATETIME,
    )
}
