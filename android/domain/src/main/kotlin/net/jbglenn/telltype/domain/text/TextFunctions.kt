package net.jbglenn.telltype.domain.text

import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.cleanup.GuardRules

/**
 * What field matching needs to know about the target (docs/PLAN.md section 4.4).
 *
 * @property kind single-line fields get no line breaks and no final period.
 * @property textBefore text before the cursor as read at record start (`""` when unknown or incognito).
 * @property textAfter text after the cursor as read at record start.
 * @property category the app category (chat categories drop the final period of short messages).
 * @property tone the tone (formal styles keep the final period).
 * @property protectedWords words whose capitalisation is never changed: dictionary terms and names.
 */
public data class FieldMatchInput(
    public val kind: FieldKind,
    public val textBefore: String,
    public val textAfter: String,
    public val category: AppCategory,
    public val tone: Tone,
    public val protectedWords: List<String>,
) {
    override fun toString(): String = "FieldMatchInput(kind=$kind, textBefore=‹${textBefore.length} chars›, " +
        "textAfter=‹${textAfter.length} chars›, category=$category, tone=$tone)"
}

/**
 * Field matching (docs/PLAN.md section 4.4): leading space after a word, lower-case first letter when continuing
 * a sentence (unless "I", a protected word or a name), final period dropped in short chat messages and kept in
 * formal styles, single-line fields without line breaks or final period, whitespace normalised. Runs on model and
 * rule-based text alike. A table of at least 60 cases is its test.
 *
 * Exact rules: line endings unified, each line trimmed and its runs of spaces, tabs and no-break spaces collapsed,
 * at most one blank line between paragraphs. A single-line (or password or numeric) field gets one line: `- `,
 * `* ` and `• ` list items lose the marker and are joined with `, `, other lines with a space. The first letter is
 * lower-cased when the text before the cursor ends, on the same line, with a letter, digit or `, ; : - – — (`,
 * unless the first word is `I` or an `I'` contraction, starts a protected phrase, has a capital after its first
 * letter (`GitHub`), or appears capitalised mid-sentence elsewhere in the text or the text before (a name). A
 * single final `.` (not `...`, not in `e.g.`) is dropped in single-line fields, before lower-case text or `, ; :`
 * after the cursor, and in a one-sentence, one-line message in a work or personal chat app or with the very casual
 * tone, never with the formal tone there. A space is added before the text when the cursor follows a letter,
 * digit, closing punctuation, a closing quote or a dash written after a space (`Note -`, not `well-`), and the
 * text does not start with punctuation that attaches to the left; and after it when a letter or digit follows the
 * cursor. Empty text stays empty.
 */
public fun matchField(text: String, field: FieldMatchInput): String = fieldMatched(text, field)

/**
 * A snippet trigger found in a transcript.
 *
 * @property marker the marker that replaces it in the transcript, for example `⟦S1⟧`.
 * @property snippet the snippet.
 */
public data class SnippetMarker(public val marker: String, public val snippet: Snippet)

/** How a transcript relates to the user's snippets (docs/PLAN.md section 4.6). */
public sealed interface SnippetMatch {
    /** No trigger found. */
    public data object None : SnippetMatch

    /**
     * The whole utterance is a trigger: insert [snippet]'s text directly and skip the model.
     *
     * @property snippet the snippet.
     */
    public data class WholeUtterance(public val snippet: Snippet) : SnippetMatch

    /**
     * Triggers inside a longer sentence were replaced by markers; clean [markedTranscript], then restore.
     *
     * @property markedTranscript the transcript with markers, `⟦` and `⟧` removed from the original text first.
     * @property markers the markers in order of appearance.
     */
    public data class Embedded(public val markedTranscript: String, public val markers: List<SnippetMarker>) :
        SnippetMatch {
        override fun toString(): String =
            "Embedded(markedTranscript=‹${markedTranscript.length} chars›, markers=${markers.size})"
    }
}

/**
 * Finds snippet triggers in [transcript] (case-insensitive, whole words), marking them with [markers]' syntax.
 * Words are runs of letters and digits with inner apostrophes; punctuation and hyphens separate words, so the
 * trigger "sign-off" matches the spoken "sign off". [SnippetMatch.WholeUtterance] when the transcript's words are
 * exactly one trigger's words (the first such snippet in list order); otherwise, left to right, the longest
 * trigger starting at a word is replaced by its snippet's marker, numbered from 1 by first appearance (the same
 * snippet twice gets the same marker); `⟦` and `⟧` (the marker's open and close) are removed from the transcript
 * first. Only as many distinct snippets as [GuardRules.SnippetMarkers.maxDigits] digits can number are marked.
 */
public fun matchSnippet(
    transcript: String,
    snippets: List<Snippet>,
    markers: GuardRules.SnippetMarkers,
): SnippetMatch = snippetMatch(transcript, snippets, markers)

/** Replaces each marker of [match] in [text] with its snippet's text. Markers not in [text] are ignored. */
public fun restoreSnippets(text: String, match: SnippetMatch.Embedded): String = snippetsRestored(text, match)

/**
 * The result of the final exact replacement step.
 *
 * @property text the text after replacement.
 * @property applied the replacements that changed something (to count their uses).
 */
public data class Replaced(public val text: String, public val applied: Set<ReplacementId>) {
    override fun toString(): String = "Replaced(text=‹${text.length} chars›, applied=$applied)"
}

/**
 * Applies the known wrong-to-right pairs as exact whole-word replacements (docs/PLAN.md section 8.4): one
 * left-to-right pass (a replacement's output is never replaced again), the longest `heard` first where several
 * match, no letter or digit directly before or after the match. A lower-case `heard` also matches with a
 * capitalised first letter (sentence start), and its replacement is then capitalised too.
 */
public fun applyReplacements(text: String, replacements: List<Replacement>): Replaced =
    replacementsApplied(text, replacements)

/**
 * Compares the field's text after an edit ([fieldText]) with what was inserted ([receipt]) and returns the single
 * word the user replaced, or null when the edit was not a one-word replacement inside the dictated text
 * (docs/PLAN.md section 4.5). The comparison starts at the receipt's range start; the rest of the inserted text
 * after the changed word must follow unchanged. A change of capitalisation only is not a correction.
 */
public fun diffCorrection(receipt: InsertReceipt, fieldText: String): Correction? = correctionOf(receipt, fieldText)
