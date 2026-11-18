package net.jbglenn.telltype.domain.cleanup

/*
 * Snippet markers (`shared/GUARDS.md` guard 6 and section 3, step 1): open + prefix + 1 to maxDigits ASCII digits
 * + close, for example `⟦S1⟧`. Matched left to right without overlap, exactly like the `regex` field (the parser
 * checks that the regex says the same), so no regular expression is compiled from data at run time.
 */

/** The UTF-16 ranges of every marker in [text], left to right, not overlapping. */
internal fun findSnippetMarkers(text: String, markers: GuardRules.SnippetMarkers): List<IntRange> {
    val found = mutableListOf<IntRange>()
    val start = markers.open + markers.prefix
    var index = if (start.isEmpty()) -1 else text.indexOf(start)
    while (index >= 0) {
        val end = markerEnd(text, index + start.length, markers)
        if (end != null) found += index until end
        index = text.indexOf(start, end ?: (index + 1))
    }
    return found
}

/** The exclusive end of a marker whose digits start at [digitsStart], or null when there is none there. */
private fun markerEnd(text: String, digitsStart: Int, markers: GuardRules.SnippetMarkers): Int? {
    var digits = 0
    while (digits < markers.maxDigits && digitsStart + digits < text.length && text[digitsStart + digits] in '0'..'9') {
        digits++
    }
    val closeAt = (digits downTo 1).map { digitsStart + it }.firstOrNull { text.startsWith(markers.close, it) }
    return closeAt?.plus(markers.close.length)
}

/** [text] with every marker replaced by a space (section 3, step 1). */
internal fun blankSnippetMarkers(text: String, markers: GuardRules.SnippetMarkers): String {
    val ranges = findSnippetMarkers(text, markers)
    if (ranges.isEmpty()) return text
    val result = StringBuilder()
    var copied = 0
    ranges.forEach { range ->
        result.append(text, copied, range.first).append(' ')
        copied = range.last + 1
    }
    return result.append(text, copied, text.length).toString()
}

/** How often each marker occurs in [text], in order of first appearance. */
internal fun countSnippetMarkers(text: String, markers: GuardRules.SnippetMarkers): Map<String, Int> =
    findSnippetMarkers(text, markers).groupingBy { text.substring(it) }.eachCount()

/** The marker with number [number], for example `⟦S3⟧`. */
internal fun snippetMarker(number: Int, markers: GuardRules.SnippetMarkers): String =
    markers.open + markers.prefix + number + markers.close
