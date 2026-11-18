package net.jbglenn.telltype.domain.cleanup

import java.text.Normalizer

/** Whether a guard token is a number or a word (`shared/GUARDS.md` section 3, step 6). */
internal enum class TokenKind {
    /** Digits, with separators between digits. */
    NUMBER,

    /** Letters, with apostrophes between letters. */
    WORD,
}

/**
 * One guard token: already normalised text and its kind.
 *
 * @property text the token text (NFKC, apostrophes unified, lower case).
 * @property kind number or word.
 */
internal data class Token(val text: String, val kind: TokenKind) {
    override fun toString(): String = "Token(kind=$kind, ‹${text.length} chars›)"
}

/** NFKC (or the configured form), apostrophe variants to `'`, lower case (GUARDS.md section 3, steps 3 to 5). */
internal fun normaliseForMatch(text: String, normalisation: GuardRules.Normalisation): String {
    val form = normalizerForm(normalisation.unicodeForm)
    var result = if (form == null) text else Normalizer.normalize(text, form)
    normalisation.apostrophes.forEach { apostrophe -> result = result.replace(apostrophe, "'") }
    return pythonLower(result)
}

/** The `java.text.Normalizer` form named [name], null for an unknown name (then no normalisation happens). */
internal fun normalizerForm(name: String): Normalizer.Form? = Normalizer.Form.entries.firstOrNull { it.name == name }

/** Removes one bullet or numbered-list marker at the start of each line, after its leading whitespace (step 2). */
internal fun stripListMarkers(text: String, normalisation: GuardRules.Normalisation): String =
    text.split("\n").joinToString("\n") { line ->
        stripOneMarker(pythonStripStart(line), normalisation.listMarkerPrefixes, normalisation.numberedListMaxDigits)
    }

private fun stripOneMarker(body: String, bullets: List<String>, maxDigits: Int): String {
    val bullet = bullets.firstOrNull { body.startsWith(it) && whitespaceAt(body, it.length) }
    return when {
        bullet != null -> pythonStripStart(body.substring(bullet.length))
        else -> stripNumberMarker(body, maxDigits)
    }
}

private fun stripNumberMarker(body: String, maxDigits: Int): String {
    var digits = 0
    while (digits < body.length && digits < maxDigits + 1 && body[digits] in '0'..'9') digits++
    val numbered = digits in 1..maxDigits && body.length > digits + 1 && body[digits] in ".)"
    return if (numbered && whitespaceAt(body, digits + 1)) pythonStripStart(body.substring(digits + 2)) else body
}

/** Whether the code point at UTF-16 index [index] of [text] exists and is Python whitespace. */
private fun whitespaceAt(text: String, index: Int): Boolean =
    index < text.length && isPythonWhitespace(text.codePointAt(index))

/** Tokenises already-normalised [text] into number and word tokens (step 6). */
internal fun tokenize(text: String, normalisation: GuardRules.Normalisation): List<Token> {
    val scanner = TokenScanner(codePointsOf(text), normalisation)
    return scanner.scan()
}

/**
 * The guard view of a text (section 3): snippet markers blanked, list markers stripped, normalised, tokenised.
 */
internal fun tokensOf(text: String, rules: GuardRules): List<Token> {
    val withoutMarkers = blankSnippetMarkers(text, rules.snippetMarkers)
    val normalised = normaliseForMatch(stripListMarkers(withoutMarkers, rules.normalisation), rules.normalisation)
    return tokenize(normalised, rules.normalisation)
}

/** The texts of [tokens]. */
internal fun textsOf(tokens: List<Token>): List<String> = tokens.map { it.text }

private class TokenScanner(private val text: IntArray, normalisation: GuardRules.Normalisation) {
    private val separators: Set<Int> = codePointsOf(normalisation.numberSeparators).toSet()
    private val suffixes: List<IntArray> = normalisation.ordinalSuffixes.map(::codePointsOf)
    private val tokens = mutableListOf<Token>()
    private var index = 0

    fun scan(): List<Token> {
        while (index < text.size) {
            val codePoint = text[index]
            when {
                isGuardDigit(codePoint) -> scanNumber()
                isGuardLetter(codePoint) -> scanWord()
                else -> index++
            }
        }
        return tokens
    }

    private fun scanNumber() {
        var end = index + 1
        while (end < text.size) {
            end = when {
                isGuardDigit(text[end]) -> end + 1
                text[end] in separators && end + 1 < text.size && isGuardDigit(text[end + 1]) -> end + 2
                else -> break
            }
        }
        tokens += Token(stringOf(text, index, end), TokenKind.NUMBER)
        index = end
        suffixes.firstOrNull(::ordinalSuffixAt)?.let { index += it.size }
    }

    private fun ordinalSuffixAt(suffix: IntArray): Boolean {
        val end = index + suffix.size
        val matches = end <= text.size && suffix.indices.all { text[index + it] == suffix[it] }
        return matches && (end == text.size || !isGuardLetter(text[end]))
    }

    private fun scanWord() {
        var end = index + 1
        while (end < text.size) {
            end = when {
                isGuardLetter(text[end]) -> end + 1
                text[end] == APOSTROPHE && end + 1 < text.size && isGuardLetter(text[end + 1]) -> end + 2
                else -> break
            }
        }
        tokens += Token(stringOf(text, index, end), TokenKind.WORD)
        index = end
    }

    private companion object {
        const val APOSTROPHE = '\''.code
    }
}
