package net.jbglenn.telltype.domain.text

import java.util.Locale
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId

/** Whether the character at [index] is part of a word (letters and digits); outside the text is not. */
private fun wordCharAt(text: String, index: Int): Boolean = text.getOrNull(index)?.isLetterOrDigit() == true

private fun capitalised(text: String): String =
    text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

/** One way a replacement can occur in the text: as written, or capitalised at a sentence start. */
private data class Form(val heard: String, val replaceWith: String, val id: ReplacementId)

/**
 * The final exact replacement (docs/PLAN.md section 8.4), in one left-to-right pass so a replacement's output is
 * never replaced again. A pair matches whole words only (no letter or digit directly before or after), exactly as
 * written; a lower-case `heard` also matches with its first letter capitalised, and then the replacement is
 * capitalised too. The longest `heard` wins where several match; blank ones are ignored.
 */
internal fun replacementsApplied(text: String, replacements: List<Replacement>): Replaced {
    val forms = replacements.filter { it.heard.isNotBlank() }.flatMap { replacement ->
        val exact = Form(replacement.heard, replacement.replaceWith, replacement.id)
        val upper = Form(capitalised(replacement.heard), capitalised(replacement.replaceWith), replacement.id)
        if (upper.heard == exact.heard) listOf(exact) else listOf(exact, upper)
    }.sortedByDescending { it.heard.length }
    val result = StringBuilder(text.length)
    val applied = mutableSetOf<ReplacementId>()
    var index = 0
    while (index < text.length) {
        val form = forms.firstOrNull { matchesAt(text, index, it.heard) }
        if (form == null) {
            result.append(text[index])
            index++
        } else {
            result.append(form.replaceWith)
            if (form.heard != form.replaceWith) applied += form.id
            index += form.heard.length
        }
    }
    return Replaced(result.toString(), applied)
}

private fun matchesAt(text: String, index: Int, heard: String): Boolean {
    val wordBefore = wordCharAt(heard, 0) && wordCharAt(text, index - 1)
    val wordAfter = wordCharAt(heard, heard.length - 1) && wordCharAt(text, index + heard.length)
    return !wordBefore && !wordAfter && text.startsWith(heard, index)
}
