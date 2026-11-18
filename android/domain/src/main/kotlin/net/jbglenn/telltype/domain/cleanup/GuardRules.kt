package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.model.cleanup.Guard

/**
 * `shared/guards.json`, parsed: the numbers, word lists and tables of the output guards and the rule-based
 * fallback (`shared/GUARDS.md`). Only the structure is fixed here; every value comes from the bundled file through
 * `parseGuardRules`, so the app and `eval/guards.py` always use the same data. Maps keep the file's key order,
 * which matters where GUARDS.md says "in file order".
 *
 * @property version the file's `version`.
 * @property order `order`: the guards in the order they run.
 * @property replyExtraction `reply_extraction` (GUARDS.md section 2).
 * @property normalisation `normalisation` (section 3).
 * @property assistantPhrasing `assistant_phrasing` (guard 2).
 * @property length `length` (guard 3).
 * @property unspokenWords `unspoken_words` (guard 4).
 * @property echo `echo` (guard 5).
 * @property snippetMarkers `snippet_markers` (guard 6, and the markers the snippet matcher inserts).
 * @property fallback `fallback` (section 5).
 */
public data class GuardRules(
    public val version: Int,
    public val order: List<Guard>,
    public val replyExtraction: ReplyExtraction,
    public val normalisation: Normalisation,
    public val assistantPhrasing: AssistantPhrasing,
    public val length: Length,
    public val unspokenWords: UnspokenWords,
    public val echo: Echo,
    public val snippetMarkers: SnippetMarkers,
    public val fallback: Fallback,
) {
    /**
     * `reply_extraction`.
     *
     * @property thinkOpen `strip_leading_think_block.open`.
     * @property thinkClose `strip_leading_think_block.close`.
     * @property unescapeEntities `unescape_entities`: entity to character, applied in one left-to-right pass.
     * @property wrappingQuotePairs `wrapping_quote_pairs`: opening and closing character, in file order.
     */
    public data class ReplyExtraction(
        public val thinkOpen: String,
        public val thinkClose: String,
        public val unescapeEntities: List<Pair<String, String>>,
        public val wrappingQuotePairs: List<Pair<String, String>>,
    )

    /**
     * `normalisation`.
     *
     * @property unicodeForm `unicode_form` (`NFKC`).
     * @property apostrophes `apostrophes`: characters replaced by `'`.
     * @property numberSeparators `number_separators`: characters that may join digits inside a number token.
     * @property ordinalSuffixes `ordinal_suffixes`.
     * @property listMarkerPrefixes `list_marker_prefixes`.
     * @property numberedListMaxDigits `numbered_list_max_digits`.
     */
    public data class Normalisation(
        public val unicodeForm: String,
        public val apostrophes: List<String>,
        public val numberSeparators: String,
        public val ordinalSuffixes: List<String>,
        public val listMarkerPrefixes: List<String>,
        public val numberedListMaxDigits: Int,
    )

    /**
     * `assistant_phrasing`.
     *
     * @property anywhere phrases that fail the reply anywhere, unless spoken.
     * @property atStart phrases that fail the reply at its start, unless spoken.
     */
    public data class AssistantPhrasing(public val anywhere: List<String>, public val atStart: List<String>)

    /**
     * `length`.
     *
     * @property upperRatio `upper_ratio`.
     * @property upperMarginTokens `upper_margin_tokens`.
     * @property lowerRatio `lower_ratio`.
     * @property lowerMinUnits `lower_min_units`.
     * @property failOnFinishReason `fail_on_finish_reason`.
     */
    public data class Length(
        public val upperRatio: Double,
        public val upperMarginTokens: Int,
        public val lowerRatio: Double,
        public val lowerMinUnits: Int,
        public val failOnFinishReason: List<String>,
    )

    /**
     * `unspoken_words`.
     *
     * @property allowanceRatio `allowance_ratio`.
     * @property ignoreSingleCharacterTokens `ignore_single_character_tokens`.
     * @property freeWords `free_words`.
     * @property equivalents `equivalents`: pairs of token lists that count as each other.
     * @property contractions `contractions`.
     * @property numberWords `number_words`.
     */
    public data class UnspokenWords(
        public val allowanceRatio: Double,
        public val ignoreSingleCharacterTokens: Boolean,
        public val freeWords: List<String>,
        public val equivalents: List<Pair<List<String>, List<String>>>,
        public val contractions: Contractions,
        public val numberWords: NumberWords,
    )

    /**
     * `unspoken_words.contractions`.
     *
     * @property suffixes `suffixes`: suffix to expansion words, in file order (checked in that order).
     * @property possessiveSuffix `possessive_suffix`.
     * @property stemOverrides `stem_overrides`.
     */
    public data class Contractions(
        public val suffixes: Map<String, List<String>>,
        public val possessiveSuffix: String,
        public val stemOverrides: Map<String, String>,
    )

    /**
     * `unspoken_words.number_words`.
     *
     * @property units `units` (including ordinals and `oh`).
     * @property teens `teens`.
     * @property tens `tens`.
     * @property scales `scales`.
     * @property months `months`.
     * @property connectors `connectors`.
     * @property maxRunWords `max_run_words`.
     */
    public data class NumberWords(
        public val units: Map<String, Long>,
        public val teens: Map<String, Long>,
        public val tens: Map<String, Long>,
        public val scales: Map<String, Long>,
        public val months: Map<String, Long>,
        public val connectors: List<String>,
        public val maxRunWords: Int,
    )

    /**
     * `echo`.
     *
     * @property ngramSize `ngram_size`.
     * @property minSourceTokens `min_source_tokens`.
     * @property ignoreNgramsWithDigits `ignore_ngrams_with_digits`.
     * @property tagNames `tag_names`.
     */
    public data class Echo(
        public val ngramSize: Int,
        public val minSourceTokens: Int,
        public val ignoreNgramsWithDigits: Boolean,
        public val tagNames: List<String>,
    )

    /**
     * `snippet_markers`: markers look like open + prefix + 1 to [maxDigits] digits + close.
     *
     * @property open `open`.
     * @property prefix `prefix`.
     * @property close `close`.
     * @property maxDigits `max_digits`.
     * @property regex `regex`: the same rule as a regular expression.
     */
    public data class SnippetMarkers(
        public val open: String,
        public val prefix: String,
        public val close: String,
        public val maxDigits: Int,
        public val regex: String,
    )

    /**
     * `fallback`.
     *
     * @property fillers `fillers`.
     * @property edgePunctuation `edge_punctuation`.
     * @property terminalPunctuation `terminal_punctuation`.
     * @property softFinalPunctuation `soft_final_punctuation`.
     * @property pronounIForms `pronoun_i_forms`.
     * @property questionOpenersWh `question_openers_wh`.
     * @property questionOpenersAux `question_openers_aux`.
     * @property questionSubjects `question_subjects`.
     */
    public data class Fallback(
        public val fillers: List<String>,
        public val edgePunctuation: String,
        public val terminalPunctuation: String,
        public val softFinalPunctuation: String,
        public val pronounIForms: List<String>,
        public val questionOpenersWh: List<String>,
        public val questionOpenersAux: List<String>,
        public val questionSubjects: List<String>,
    )
}
