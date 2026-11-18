package net.jbglenn.telltype.domain.text

/**
 * Line prefixes that mark a list item: the cleanup prompt writes lists as `- item` lines; the other bullets are
 * accepted as well. A single-line field gets the items joined with commas instead.
 */
private val ListItemPrefixes = listOf("- ", "* ", "• ")

/** Lines trimmed with inner whitespace runs collapsed; at most one blank line; single-line: one line. */
internal fun normaliseWhitespace(text: String, singleLine: Boolean): String {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { line ->
        line.split(' ', '\t', '\u00A0').filter { it.isNotEmpty() }.joinToString(" ").trim()
    }
    return if (singleLine) joinSingleLine(lines.filter { it.isNotEmpty() }) else joinParagraphs(lines)
}

private fun joinParagraphs(lines: List<String>): String {
    val out = StringBuilder()
    var blank = false
    lines.forEach { line ->
        when {
            line.isEmpty() -> blank = out.isNotEmpty()

            else -> {
                if (out.isNotEmpty()) out.append(if (blank) "\n\n" else "\n")
                out.append(line)
                blank = false
            }
        }
    }
    return out.toString()
}

private fun joinSingleLine(lines: List<String>): String {
    val out = StringBuilder()
    var previousWasItem = false
    lines.forEach { line ->
        val prefix = ListItemPrefixes.firstOrNull { line.startsWith(it) }
        val body = prefix?.let { line.removePrefix(it).trim() } ?: line
        if (out.isNotEmpty()) out.append(if (prefix != null && previousWasItem) ", " else " ")
        out.append(body)
        previousWasItem = prefix != null
    }
    return out.toString()
}

/** A first character of dictated text that attaches to what is before it (no space in between). */
private fun attachesLeft(char: Char): Boolean = char in ",.;:!?…)]}%”’"

/** A dash the user wrote with a space before it (`Note -`, `Wait —`) gets one after it too; `well-` does not. */
private fun spacedDash(before: String): Boolean =
    before.lastOrNull()?.let { it in "-–—" } == true && before.dropLast(1).lastOrNull()?.isWhitespace() == true

/** The space before the dictated [text], if any, after the field text [before]. */
internal fun leadingSpace(before: String, text: String): String {
    val last = before.lastOrNull()
    val closingQuote = last == '"' && before.count { it == '"' } % 2 == 0
    val afterWord = last != null && (last.isLetterOrDigit() || last in ".,;:!?…)]}%”’'*" || closingQuote)
    return if ((afterWord || spacedDash(before)) && !attachesLeft(text.first())) " " else ""
}

/** The space after the dictated [text], if any, before the field text [after]. */
internal fun trailingSpace(text: String, after: String): String =
    if (after.firstOrNull()?.isLetterOrDigit() == true && !text.last().isWhitespace()) " " else ""
