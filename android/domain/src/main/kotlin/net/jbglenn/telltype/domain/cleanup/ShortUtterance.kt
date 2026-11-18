package net.jbglenn.telltype.domain.cleanup

import java.util.Locale

/**
 * The short-phrase rule of docs/PLAN.md section 8.3 ("utterances of a few words with no correction cue skip the
 * model"). The plan gives no numbers and `shared/` has no file for them, so they live here as named constants (A2b
 * decision). Measured on the golden set: up to three words, rule-based cleanup matches the expected text in 4 of 4
 * `text` cases and 3 of 4 `asr` cases; at four words dictionary cases start to fail.
 */
internal object ShortUtterance {
    /** At most this many words count as short. */
    const val MAX_WORDS: Int = 3

    /**
     * Words or phrases that signal a self-correction, so the model must run however short the utterance is. The
     * correction words of the cleanup system prompt (`shared/prompts/cleanup_system.txt`, edit 2) and the plan's
     * Appendix D, lower case, as word sequences.
     */
    val CorrectionCues: List<List<String>> = listOf(
        listOf("no"),
        listOf("wait"),
        listOf("actually"),
        listOf("sorry"),
        listOf("i", "mean"),
        listOf("scratch", "that"),
        listOf("make", "that"),
    )
}

/** The words of [transcript] for the short-phrase rule: lower case, edge punctuation removed, `’` as `'`. */
internal fun shortUtteranceWords(transcript: String): List<String> = pythonSplit(transcript)
    .map { piece -> piece.lowercase(Locale.ROOT).replace('’', '\'').trim { !it.isLetterOrDigit() && it != '\'' } }
    .filter { it.isNotEmpty() }

/** Whether [transcript] has between one and [ShortUtterance.MAX_WORDS] words and contains no correction cue. */
internal fun isShort(transcript: String): Boolean {
    val words = shortUtteranceWords(transcript)
    val hasCue = ShortUtterance.CorrectionCues.any { cue -> words.windowed(cue.size).any { it == cue } }
    return words.size in 1..ShortUtterance.MAX_WORDS && !hasCue
}
