package net.jbglenn.telltype.platform.insert

import net.jbglenn.telltype.core.model.field.TextRange

/** Text-splicing helpers of the insertion ladder. Pure; UTF-16 indices like Android selections. */
internal object Splice {
    /** [text] with [range] replaced by [insert]. */
    fun apply(text: String, range: TextRange, insert: String): String =
        text.substring(0, range.start) + insert + text.substring(range.end)

    /**
     * Where [inserted] went when [after] is [before] with one range replaced by [inserted], for fields that do not
     * report a selection. Returns the first explanation, or null when [after] cannot be explained that way.
     */
    fun locate(before: String, after: String, inserted: String): Located? {
        val replacedLength = before.length + inserted.length - after.length
        if (replacedLength < 0) return null
        return (0..after.length - inserted.length).asSequence()
            .filter { start -> start + replacedLength <= before.length }
            .filter { start -> after.startsWith(inserted, start) }
            .filter { start -> after.regionMatches(0, before, 0, start) }
            .filter { start -> after.endsWith(before.substring(start + replacedLength)) }
            .map { start ->
                Located(TextRange(start, start + inserted.length), before.substring(start, start + replacedLength))
            }
            .firstOrNull()
    }

    /**
     * [landed] is [inserted] as a field may adapt typed text without losing the dictation (A7h):
     * - case may change (auto-capitalisation) and characters may be left out (an input filter), but nothing is added
     *   or reordered;
     * - punctuation, spaces and symbols may all go, but of the letters and digits at least 90 % stay
     *   ([MIN_KEPT_PERCENT]) and the last one stays: a field with a length limit cuts the end, so text whose last
     *   letter or digit is missing was truncated, even by one character, and is not typed (the user gets the
     *   hand-off and can paste it elsewhere);
     * - blank text is no adaptation.
     */
    fun isAdaptation(landed: String, inserted: String): Boolean {
        val wanted = inserted.lowercase()
        var next = 0
        val inOrder = landed.lowercase().all { char ->
            next = wanted.indexOf(char, next) + 1
            next > 0
        }
        return landed.isNotBlank() && inOrder && keepsNearlyAllWords(landed.lowercase(), wanted)
    }

    private fun keepsNearlyAllWords(landed: String, wanted: String): Boolean {
        val wantedWords = wanted.filter(Char::isLetterOrDigit)
        val keptWords = landed.filter(Char::isLetterOrDigit)
        val enough = keptWords.length * PERCENT >= wantedWords.length * MIN_KEPT_PERCENT
        return wantedWords.isEmpty() || (enough && keptWords.lastOrNull() == wantedWords.last())
    }

    /** The share of the dictation's letters and digits a field must keep for [isAdaptation]. */
    const val MIN_KEPT_PERCENT: Int = 90

    private const val PERCENT = 100

    /**
     * An explanation found by [locate].
     *
     * @property inserted where the inserted text sits in the new text.
     * @property replaced the text of the old field it replaced (`""` for a plain cursor). On-screen text.
     */
    data class Located(val inserted: TextRange, val replaced: String) {
        override fun toString(): String = "Located(inserted=$inserted, replaced=${replaced.length} chars)"
    }
}
