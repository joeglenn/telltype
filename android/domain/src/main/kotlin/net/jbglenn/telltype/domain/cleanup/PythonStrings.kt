package net.jbglenn.telltype.domain.cleanup

/* String operations with Python semantics (code points, Python whitespace); see CharacterClasses.kt. */

/** The code points of [text]. */
internal fun codePointsOf(text: String): IntArray = text.codePoints().toArray()

/** A string made of [codePoints] from [start] (inclusive) to [end] (exclusive). */
internal fun stringOf(codePoints: IntArray, start: Int = 0, end: Int = codePoints.size): String =
    String(codePoints, start, end - start)

/** Python `str.strip()`: leading and trailing Python whitespace removed. */
internal fun pythonStrip(text: String): String = pythonStripEnd(pythonStripStart(text))

/** Python `str.lstrip()`. */
internal fun pythonStripStart(text: String): String {
    var index = 0
    while (index < text.length && isPythonWhitespace(text.codePointAt(index))) {
        index += Character.charCount(text.codePointAt(index))
    }
    return text.substring(index)
}

/** Python `str.rstrip()`. */
internal fun pythonStripEnd(text: String): String {
    var end = text.length
    while (end > 0 && isPythonWhitespace(text.codePointBefore(end))) {
        end -= Character.charCount(text.codePointBefore(end))
    }
    return text.substring(0, end)
}

/** Python `str.split()` without arguments: split on runs of Python whitespace, no empty pieces. */
internal fun pythonSplit(text: String): List<String> {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    text.codePoints().forEach { codePoint ->
        if (isPythonWhitespace(codePoint)) {
            if (current.isNotEmpty()) pieces += current.toString()
            current.setLength(0)
        } else {
            current.appendCodePoint(codePoint)
        }
    }
    if (current.isNotEmpty()) pieces += current.toString()
    return pieces
}

/** Number of code points in [text] (Python `len`). */
internal fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

/** The last [limit] code points of [text] (Python `text[-limit:]`); `""` when [limit] is not positive. */
internal fun lastCodePoints(text: String, limit: Int): String {
    val length = codePointLength(text)
    return when {
        limit <= 0 -> ""
        limit >= length -> text
        else -> text.substring(text.offsetByCodePoints(0, length - limit))
    }
}
