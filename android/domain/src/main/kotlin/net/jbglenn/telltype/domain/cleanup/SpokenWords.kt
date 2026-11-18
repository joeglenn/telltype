package net.jbglenn.telltype.domain.cleanup

/**
 * A contraction split into stem, suffix and expansion words (`shared/GUARDS.md` guard 4, "Contraction").
 *
 * @property stem the word without the suffix, through `stem_overrides`.
 * @property suffix the matched suffix.
 * @property expansions the suffix's expansion words.
 */
private data class Contraction(val stem: String, val suffix: String, val expansions: List<String>)

/** The first suffix of `contractions.suffixes` (file order) that [word] ends with and is longer than. */
private fun splitContraction(word: String, contractions: GuardRules.Contractions): Contraction? =
    contractions.suffixes.entries.firstOrNull { (suffix, _) -> word.endsWith(suffix) && word.length > suffix.length }
        ?.let { (suffix, expansions) ->
            val stem = word.dropLast(suffix.length)
            Contraction(contractions.stemOverrides[stem] ?: stem, suffix, expansions)
        }

/** Adds [word] and, for a contraction, its stem and every expansion word. */
private fun addSpoken(spoken: MutableSet<String>, word: String, contractions: GuardRules.Contractions) {
    spoken += word
    splitContraction(word, contractions)?.let { contraction ->
        spoken += contraction.stem
        spoken += contraction.expansions
    }
}

/** The spoken-word set `S`: every word token of [sources], contractions expanded, equivalents to a fixed point. */
private fun spokenWords(sources: List<List<Token>>, words: GuardRules.UnspokenWords): Set<String> {
    val spoken = mutableSetOf<String>()
    sources.flatten().filter { it.kind == TokenKind.WORD }.forEach { addSpoken(spoken, it.text, words.contractions) }
    val directions = words.equivalents.flatMap { (left, right) -> listOf(left to right, right to left) }
    var changed = true
    while (changed) {
        val missing = directions.filter { (have, add) -> spoken.containsAll(have) && !spoken.containsAll(add) }
        missing.forEach { (_, add) -> add.forEach { addSpoken(spoken, it, words.contractions) } }
        changed = missing.isNotEmpty()
    }
    return spoken
}

private fun wordSpoken(
    word: String,
    spoken: Set<String>,
    values: Set<String>,
    words: GuardRules.UnspokenWords,
): Boolean {
    val table = words.numberWords
    val contraction = splitContraction(word, words.contractions)
    val contractionSpoken = contraction != null && contraction.stem in spoken &&
        (contraction.suffix == words.contractions.possessiveSuffix || contraction.expansions.any { it in spoken })
    val valueSpoken = listOfNotNull(numberWordValue(word, table), table.months[word]).any { it.toString() in values }
    return word in spoken ||
        word in words.freeWords ||
        (words.ignoreSingleCharacterTokens && codePointLength(word) == 1) ||
        valueSpoken ||
        contractionSpoken
}

/**
 * The reply tokens that were never spoken, in reply order, one per occurrence (GUARDS.md guard 4). Spoken words
 * come from the transcript, the context before and the dictionary; spoken numbers from the transcript only.
 */
internal fun unspokenTokens(subject: GuardSubject, rules: GuardRules): List<String> {
    val sources = listOf(subject.transcriptTokens, tokensOf(subject.contextBefore, rules)) +
        subject.dictionary.map { tokensOf(it, rules) }
    val spoken = spokenWords(sources, rules.unspokenWords)
    val values = spokenNumberValues(subject.transcriptTokens, rules)
    return subject.replyTokens.filterNot { token ->
        when (token.kind) {
            TokenKind.NUMBER -> numberTokenSpoken(token.text, values, rules.normalisation)
            TokenKind.WORD -> wordSpoken(token.text, spoken, values, rules.unspokenWords)
        }
    }.map { it.text }
}
