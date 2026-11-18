package net.jbglenn.telltype.domain.cleanup

/** Step 7: final punctuation, `?` for a question. */
internal fun finalPunctuation(text: String, rules: GuardRules, sets: FallbackSets): String {
    val last = lastCodePoint(text)
    val body = if (last in sets.soft) dropLastCodePoint(text) else text
    val sentence = normaliseForMatch(lastSentence(body, sets.terminal), rules.normalisation)
    return when {
        last in sets.terminal -> text
        isQuestion(sentence, rules.fallback, sets) -> "$body?"
        else -> "$body."
    }
}

/** The text after the last terminal mark that is followed by a space (all of [text] when there is none). */
private fun lastSentence(text: String, terminal: Set<Int>): String {
    var cut = 0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val next = index + Character.charCount(codePoint)
        if (codePoint in terminal && next < text.length && text[next] == ' ') cut = next + 1
        index = next
    }
    return text.substring(cut)
}

private fun isQuestion(sentence: String, fallback: GuardRules.Fallback, sets: FallbackSets): Boolean {
    val words = sentence.split(" ").filter { it.isNotEmpty() }
        .map { pythonLower(splitChunk(it, sets.edge).core) }
        .filter { it.isNotEmpty() }
    val first = words.firstOrNull()
    return when {
        first == null -> false
        first in fallback.questionOpenersWh -> true
        else -> first in fallback.questionOpenersAux && words.size > 1 && words[1] in fallback.questionSubjects
    }
}
