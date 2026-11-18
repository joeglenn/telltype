package net.jbglenn.telltype.domain.coordinator

import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.domain.text.FieldMatchInput
import net.jbglenn.telltype.domain.text.SnippetMatch
import net.jbglenn.telltype.domain.text.applyReplacements
import net.jbglenn.telltype.domain.text.isSoundsLikeReplacement
import net.jbglenn.telltype.domain.text.matchField
import net.jbglenn.telltype.domain.text.restoreSnippets
import net.jbglenn.telltype.domain.text.soundsLikeReplacements

/**
 * What the text stage of one session reads: the user's snippets, replacements and dictionary terms, the context read
 * at record start (empty for incognito fields) and the field-matching input built from them.
 */
internal data class TextInputs(
    val snippets: List<Snippet>,
    val replacements: List<Replacement>,
    val terms: List<DictionaryTerm>,
    val context: FieldContext,
    val field: FieldMatchInput,
    val incognito: Boolean,
) {
    override fun toString(): String = "TextInputs(snippets=${snippets.size}, replacements=${replacements.size}, " +
        "terms=${terms.size}, context=$context, field=$field, incognito=$incognito)"
}

/**
 * The result of post-processing.
 *
 * @property text the text to insert.
 * @property replacements the replacements that changed something (their use counts go up).
 */
internal data class Processed(val text: String, val replacements: Set<ReplacementId>) {
    override fun toString(): String = "Processed(text=‹${text.length} chars›, replacements=$replacements)"
}

/**
 * The rule-based post-processing of docs/PLAN.md section 8.4, run on every result, model or rules, in this fixed
 * order: (1) dictionary replacements (`applyReplacements`: the stored pairs and each term's "sounds like" forms,
 * before the snippets come back so a snippet's saved text is never rewritten); (2) snippet restore (markers back
 * to their saved text); (3) field matching (`matchField`, with
 * the text before and after the cursor read at record start and the dictionary terms as protected words), which
 * (4) also normalises whitespace as its first step.
 */
internal fun postProcess(text: String, match: SnippetMatch, inputs: TextInputs): Processed {
    val replaced = applyReplacements(text, inputs.replacements + soundsLikeReplacements(inputs.terms))
    val restored = if (match is SnippetMatch.Embedded) restoreSnippets(replaced.text, match) else replaced.text
    val stored = replaced.applied.filterNot(::isSoundsLikeReplacement).toSet()
    return Processed(matchField(restored, inputs.field), stored)
}

/** The transcript the model or the rules see: with snippet markers when triggers were embedded. */
internal fun SnippetMatch.modelInput(transcript: String): String =
    (this as? SnippetMatch.Embedded)?.markedTranscript ?: transcript
