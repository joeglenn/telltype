package net.jbglenn.telltype.domain.cleanup

import java.util.Locale

/*
 * Character classes and string operations with the semantics of the Python reference (eval/guards.py,
 * eval/prompt_builder.py). Python strings are sequences of code points and its whitespace set differs from
 * Java's, so every helper here works on code points and uses Python's definitions. shared/GUARDS.md, "All
 * matching is on Unicode code points".
 */

/**
 * The code points for which Python 3.12 `str.isspace()` is true: Java's `Character.isWhitespace` lacks U+0085,
 * U+00A0, U+2007 and U+202F, so it cannot be used.
 */
private val PythonWhitespace: Set<Int> = (0x09..0x0D).toSet() + (0x1C..0x20) + setOf(0x85, 0xA0, 0x1680) +
    (0x2000..0x200A) + setOf(0x2028, 0x2029, 0x202F, 0x205F, 0x3000)

/** Python `str.isspace()` for one code point. */
internal fun isPythonWhitespace(codePoint: Int): Boolean = codePoint in PythonWhitespace

/** GUARDS.md "letter": general category `L*` or `M*`, or `Nl` / `No`. */
internal fun isGuardLetter(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
    Character.MODIFIER_LETTER, Character.OTHER_LETTER,
    -> true

    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true

    Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true

    else -> false
}

/** GUARDS.md "digit": general category `Nd`. */
internal fun isGuardDigit(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER.toInt()

/** Python `str.lower()` (root locale; GUARDS.md section "All matching"). */
internal fun pythonLower(text: String): String = text.lowercase(Locale.ROOT)

/** Python `ch.upper()` kept only when it is a single code point (GUARDS.md section 5, step 6). */
internal fun upperIfSingle(codePoint: Int): Int {
    val upper = String(Character.toChars(codePoint)).uppercase(Locale.ROOT)
    return if (upper.codePointCount(0, upper.length) == 1) upper.codePointAt(0) else codePoint
}
