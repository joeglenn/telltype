package net.jbglenn.telltype.domain.text

import java.util.Locale
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.snippetMarker

/**
 * One word of a text for snippet matching: letters and digits with inner apostrophes, lower case, `’` as `'`.
 *
 * @property start UTF-16 index of the first character.
 * @property end UTF-16 index after the last character.
 * @property word the comparable form.
 */
private data class WordSpan(val start: Int, val end: Int, val word: String)

private fun isSnippetWordChar(text: String, index: Int): Boolean {
    val char = text[index]
    val apostrophe = (char == '\'' || char == '’') && index > 0 && index + 1 < text.length &&
        text[index - 1].isLetterOrDigit() && text[index + 1].isLetterOrDigit()
    return char.isLetterOrDigit() || apostrophe
}

private fun wordSpans(text: String): List<WordSpan> {
    val spans = mutableListOf<WordSpan>()
    var index = 0
    while (index < text.length) {
        if (isSnippetWordChar(text, index)) {
            val start = index
            while (index < text.length && isSnippetWordChar(text, index)) index++
            spans += WordSpan(start, index, text.substring(start, index).lowercase(Locale.ROOT).replace('’', '\''))
        } else {
            index++
        }
    }
    return spans
}

/** One occurrence of a trigger: the transcript characters it covers and its snippet. */
private data class TriggerHit(val start: Int, val end: Int, val snippet: Snippet)

/** Snippet matching (docs/PLAN.md section 4.6); see `matchSnippet`. */
internal fun snippetMatch(
    transcript: String,
    snippets: List<Snippet>,
    markers: GuardRules.SnippetMarkers,
): SnippetMatch {
    val clean = transcript.replace(markers.open, "").replace(markers.close, "")
    val words = wordSpans(clean)
    val triggers = snippets.map { it to wordSpans(it.trigger).map(WordSpan::word) }.filter { it.second.isNotEmpty() }
    val whole = triggers.firstOrNull { (_, trigger) -> trigger == words.map(WordSpan::word) }?.first
    return when {
        whole != null -> SnippetMatch.WholeUtterance(whole)
        else -> embedded(clean, findHits(words, triggers.sortedByDescending { it.second.size }), markers)
    }
}

/** Left to right, the longest trigger at each word wins; hits do not overlap. */
private fun findHits(words: List<WordSpan>, triggers: List<Pair<Snippet, List<String>>>): List<TriggerHit> {
    val hits = mutableListOf<TriggerHit>()
    var index = 0
    while (index < words.size) {
        val hit = triggers.firstOrNull { (_, trigger) ->
            index + trigger.size <= words.size && trigger.indices.all { words[index + it].word == trigger[it] }
        }
        if (hit == null) {
            index++
        } else {
            hits += TriggerHit(words[index].start, words[index + hit.second.size - 1].end, hit.first)
            index += hit.second.size
        }
    }
    return hits
}

/** Replaces each hit by its snippet's marker; markers are numbered by first appearance, as many as fit. */
private fun embedded(text: String, hits: List<TriggerHit>, markers: GuardRules.SnippetMarkers): SnippetMatch {
    val limit = maxMarkers(markers.maxDigits)
    val numbered = hits.map { it.snippet.id }.distinct().take(limit)
    val used = hits.filter { it.snippet.id in numbered }
    if (used.isEmpty()) return SnippetMatch.None
    val marked = StringBuilder()
    var copied = 0
    used.forEach { hit ->
        marked.append(text, copied, hit.start).append(snippetMarker(numbered.indexOf(hit.snippet.id) + 1, markers))
        copied = hit.end
    }
    marked.append(text, copied, text.length)
    val list = numbered.map { id -> used.first { it.snippet.id == id }.snippet }
        .mapIndexed { index, snippet -> SnippetMarker(snippetMarker(index + 1, markers), snippet) }
    return SnippetMatch.Embedded(marked.toString(), list)
}

/** How many distinct markers fit in [maxDigits] digits (`10^maxDigits - 1`, at least 0). */
private fun maxMarkers(maxDigits: Int): Int {
    var limit = 1L
    repeat(maxDigits.coerceIn(0, MarkerNumbers.MAX_DIGITS)) { limit *= MarkerNumbers.BASE }
    return (limit - 1).toInt()
}

private object MarkerNumbers {
    /** More digits than this cannot be numbered with an `Int` anyway. */
    const val MAX_DIGITS = 9

    /** Markers are numbered in decimal. */
    const val BASE = 10L
}

/** Each marker of [match] in [text] replaced by its snippet's text, in one pass. */
internal fun snippetsRestored(text: String, match: SnippetMatch.Embedded): String {
    val result = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val marker = match.markers.firstOrNull { it.marker.isNotEmpty() && text.startsWith(it.marker, index) }
        if (marker == null) {
            result.append(text[index])
            index++
        } else {
            result.append(marker.snippet.text)
            index += marker.marker.length
        }
    }
    return result.toString()
}
