package net.jbglenn.telltype.domain.cleanup

/** Leading ASCII zeros removed; `"0"` when nothing is left. */
internal fun stripLeadingZeros(digits: String): String = digits.trimStart('0').ifEmpty { "0" }

/** The digit groups of a number token, split at each number separator (empty groups kept, like `re.split`). */
internal fun numberGroups(token: String, normalisation: GuardRules.Normalisation): List<String> {
    val separators = codePointsOf(normalisation.numberSeparators).toSet()
    val groups = mutableListOf<String>()
    val current = StringBuilder()
    token.codePoints().forEach { codePoint ->
        if (codePoint in separators) {
            groups += current.toString()
            current.setLength(0)
        } else {
            current.appendCodePoint(codePoint)
        }
    }
    groups += current.toString()
    return groups
}

/** Whether a reply number token was spoken: its joined groups, or every group, are zero or in [values]. */
internal fun numberTokenSpoken(token: String, values: Set<String>, normalisation: GuardRules.Normalisation): Boolean {
    val groups = numberGroups(token, normalisation)
    val joined = stripLeadingZeros(groups.joinToString(""))
    return joined in values || groups.map(::stripLeadingZeros).all { it == "0" || it in values }
}
