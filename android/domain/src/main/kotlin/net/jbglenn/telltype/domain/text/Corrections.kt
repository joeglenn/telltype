package net.jbglenn.telltype.domain.text

import java.util.Locale
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.insert.InsertReceipt

/** Letters, digits, combining marks, apostrophes and hyphens form a word for learning. */
private fun isLearnWordChar(char: Char): Boolean =
    char.isLetterOrDigit() || char == '\'' || char == '’' || char == '-' || Character.getType(char) == NonSpacingMark

private val NonSpacingMark = Character.NON_SPACING_MARK.toInt()

/**
 * The learnable correction (docs/PLAN.md section 4.5): the user replaced exactly one word of the inserted text
 * with one other word, and the rest of the inserted text is unchanged after it. The comparison starts at the
 * receipt's range start in [fieldText]; text before the dictation is assumed unchanged and text after it is
 * ignored. Null for anything else: not typed, no range, the range is outside the field, several words changed, a
 * word was only added or deleted, or only its capitalisation changed.
 */
internal fun correctionOf(receipt: InsertReceipt, fieldText: String): Correction? {
    val start = receipt.range?.start
    return if (receipt.via.typed && start != null && start <= fieldText.length) {
        correctionIn(receipt.text, fieldText.substring(start))
    } else {
        null
    }
}

private fun correctionIn(inserted: String, region: String): Correction? {
    var common = 0
    while (common < inserted.length && common < region.length && inserted[common] == region[common]) common++
    while (common > 0 && isLearnWordChar(inserted[common - 1])) common--
    val oldEnd = wordEnd(inserted, common)
    val newEnd = wordEnd(region, common)
    val heard = inserted.substring(common, oldEnd)
    val typed = region.substring(common, newEnd)
    val restKept = region.startsWith(inserted.substring(oldEnd), newEnd)
    val oneWordReplaced = heard.isNotEmpty() && typed.isNotEmpty() &&
        heard.lowercase(Locale.ROOT) != typed.lowercase(Locale.ROOT)
    return if (restKept && oneWordReplaced) Correction(heard, typed) else null
}

private fun wordEnd(text: String, from: Int): Int {
    var end = from
    while (end < text.length && isLearnWordChar(text[end])) end++
    return end
}
