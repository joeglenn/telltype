package net.jbglenn.telltype.domain.cleanup

/**
 * One whitespace-separated piece of the transcript (`shared/GUARDS.md` section 5, step 2).
 *
 * @property lead the longest prefix of edge punctuation.
 * @property core the rest.
 * @property trail the longest suffix of edge punctuation not overlapping [lead].
 */
internal class Chunk(val lead: String, var core: String, var trail: String) {
    fun text(): String = lead + core + trail

    override fun toString(): String = "Chunk(‹${text().length} chars›)"
}

/** The character sets of `fallback` as code points. */
internal class FallbackSets(rules: GuardRules.Fallback) {
    val edge: Set<Int> = codePointsOf(rules.edgePunctuation).toSet()
    val terminal: Set<Int> = codePointsOf(rules.terminalPunctuation).toSet()
    val soft: Set<Int> = codePointsOf(rules.softFinalPunctuation).toSet()
}

/** The rule-based fallback cleanup (GUARDS.md section 5). Mirrors `fallback_cleanup` in `eval/guards.py`. */
internal fun fallbackCleanup(transcript: String, rules: GuardRules): String {
    val fallback = rules.fallback
    val sets = FallbackSets(fallback)
    val kept = dropFillers(pythonSplit(transcript).map { splitChunk(it, sets.edge) }, fallback, sets)
    kept.forEach { fixPronounI(it, rules) }
    val text = pythonStrip(kept.joinToString(" ") { it.text() })
    return if (text.isEmpty()) "" else finalPunctuation(capitaliseSentences(text, sets.terminal), rules, sets)
}

/** Splits a piece into lead, core and trail (step 2). */
internal fun splitChunk(chunk: String, edge: Set<Int>): Chunk {
    var start = 0
    while (start < chunk.length && chunk.codePointAt(start) in edge) {
        start += Character.charCount(chunk.codePointAt(start))
    }
    var end = chunk.length
    while (end > start && chunk.codePointBefore(end) in edge) {
        end -= Character.charCount(chunk.codePointBefore(end))
    }
    return Chunk(chunk.substring(0, start), chunk.substring(start, end), chunk.substring(end))
}

/** The last code point of non-empty [text]. */
internal fun lastCodePoint(text: String): Int = text.codePointBefore(text.length)

/** Non-empty [text] without its last code point. */
internal fun dropLastCodePoint(text: String): String = text.substring(0, text.offsetByCodePoints(text.length, -1))

private fun dropFillers(chunks: List<Chunk>, fallback: GuardRules.Fallback, sets: FallbackSets): List<Chunk> {
    val kept = mutableListOf<Chunk>()
    chunks.forEach { chunk ->
        val previous = kept.lastOrNull()
        when {
            chunk.core.isEmpty() -> previous?.let { it.trail += chunk.lead + chunk.trail }
            pythonLower(chunk.core) !in fallback.fillers -> kept += chunk
            previous != null -> carryTerminal(chunk.trail, previous, sets)
            else -> Unit
        }
    }
    return kept
}

/** A dropped filler's last terminal mark moves to the previous kept chunk (step 3, second bullet). */
private fun carryTerminal(fillerTrail: String, previous: Chunk, sets: FallbackSets) {
    val mark = codePointsOf(fillerTrail).lastOrNull { it in sets.terminal }?.let { String(Character.toChars(it)) }
    when {
        mark == null -> Unit
        previous.trail.endsWith(",") -> previous.trail = dropLastCodePoint(previous.trail) + mark
        previous.trail.isEmpty() || lastCodePoint(previous.trail) !in sets.edge -> previous.trail += mark
        else -> Unit
    }
}

private fun fixPronounI(chunk: Chunk, rules: GuardRules) {
    val pronoun = normaliseForMatch(chunk.core, rules.normalisation) in rules.fallback.pronounIForms
    if (chunk.core.startsWith("i") && pronoun) chunk.core = "I" + chunk.core.substring(1)
}

private fun capitaliseSentences(text: String, terminal: Set<Int>): String {
    val out = StringBuilder(text.length)
    var atStart = true
    var afterTerminal = false
    text.codePoints().forEach { codePoint ->
        val isLetter = isGuardLetter(codePoint)
        out.appendCodePoint(if (atStart && isLetter) upperIfSingle(codePoint) else codePoint)
        when {
            isLetter || isGuardDigit(codePoint) -> {
                atStart = false
                afterTerminal = false
            }

            codePoint in terminal -> afterTerminal = true

            isPythonWhitespace(codePoint) -> {
                if (afterTerminal) atStart = true
                afterTerminal = false
            }

            else -> afterTerminal = false
        }
    }
    return out.toString()
}
