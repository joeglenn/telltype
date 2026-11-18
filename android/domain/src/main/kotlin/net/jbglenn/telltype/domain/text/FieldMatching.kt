package net.jbglenn.telltype.domain.text

import java.util.Locale
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/*
 * Field matching (docs/PLAN.md section 4.4). Plain rules after cleanup, no model: whitespace normalised, a space
 * before the text when the cursor follows a word or punctuation and after it when a word follows, a lower-case
 * first letter when the text continues a sentence (never for "I", protected words or names), no final period in
 * short chat messages, in mid-sentence insertions and in single-line fields, which also get no line breaks.
 */

/** Categories whose short messages lose their final period. */
private val ChatCategories = setOf(AppCategory.WORK_CHAT, AppCategory.PERSONAL_CHAT)

/** A character before the cursor after which the dictated text continues the sentence. */
private fun continuesSentence(char: Char): Boolean = char.isLetterOrDigit() || char in ",;:-–—("

/** Field matching of [text] for [field]. */
internal fun fieldMatched(text: String, field: FieldMatchInput): String {
    val singleLine = field.kind != FieldKind.MULTI_LINE
    val normalised = normaliseWhitespace(text, singleLine)
    val cased = lowerFirstWhenContinuing(normalised, field)
    val ended = if (dropsFinalPeriod(cased, field, singleLine)) cased.dropLast(1) else cased
    return when {
        ended.isEmpty() -> ""
        else -> leadingSpace(field.textBefore, ended) + ended + trailingSpace(ended, field.textAfter)
    }
}

private fun lowerFirstWhenContinuing(text: String, field: FieldMatchInput): String {
    val before = field.textBefore.trimEnd()
    val sameLine = '\n' !in field.textBefore.substring(before.length)
    val continuing = before.isNotEmpty() && sameLine && continuesSentence(before.last())
    val word = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }
    val lowerable = word.isNotEmpty() && word.first().isUpperCase() && word.drop(1).none { it.isUpperCase() }
    val keep = word == "I" || word.startsWith("I'") || word.startsWith("I’") ||
        isProtected(text, field.protectedWords) || isName(word, text, field.textBefore)
    return if (continuing && lowerable && !keep) text.first().lowercase(Locale.ROOT) + text.substring(1) else text
}

/** [text] starts with a protected word or phrase, exactly as written, followed by a non-letter. */
private fun isProtected(text: String, protectedWords: List<String>): Boolean = protectedWords.any { term ->
    term.isNotBlank() && text.startsWith(term) && text.getOrNull(term.length)?.isLetterOrDigit() != true
}

/** [word] also appears capitalised in the middle of a sentence of [text] or [before]: it is a name. */
private fun isName(word: String, text: String, before: String): Boolean {
    if (word.isEmpty()) return false
    val pattern = Regex("(?<=[\\p{L}\\p{N},;:] )" + Regex.escape(word) + "(?![\\p{L}\\p{N}])")
    return pattern.containsMatchIn(text) || pattern.containsMatchIn(before)
}

private fun dropsFinalPeriod(text: String, field: FieldMatchInput, singleLine: Boolean): Boolean {
    val lastWord = text.substringAfterLast(' ').substringAfterLast('\n')
    val plainPeriod = text.endsWith(".") && lastWord.count { it == '.' } == 1
    val nextChar = field.textAfter.trimStart().firstOrNull()
    val midSentence = nextChar != null && (nextChar.isLowerCase() || nextChar in ",;:")
    val chatStyle = (field.category in ChatCategories || field.tone == Tone.VERY_CASUAL) && field.tone != Tone.FORMAL
    return plainPeriod && (singleLine || midSentence || (chatStyle && isShortMessage(text)))
}

/** One line holding one sentence: no terminal mark followed by a space before the end. */
private fun isShortMessage(text: String): Boolean =
    '\n' !in text && text.dropLast(1).windowed(2).none { it[0] in ".!?…" && it[1] == ' ' }
