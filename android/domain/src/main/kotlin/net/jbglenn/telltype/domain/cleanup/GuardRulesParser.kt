package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.cleanup.Guard

private object GuardsFormat {
    /** The file name in errors. */
    const val FILE = "guards.json"

    /** Characters with a meaning in a regular expression; marker parts must not contain them. */
    const val REGEX_METACHARACTERS = "\\^$.|?*+()[]{}"
}

private val TopLevelKeys = setOf(
    "version", "order", "reply_extraction", "normalisation", "empty", "assistant_phrasing", "length",
    "unspoken_words", "echo", "snippet_markers", "fallback",
)

/** Parses `shared/guards.json` strictly: every key present with its type, no unknown keys, values consistent. */
internal fun decodeGuardRules(json: String): Outcome<GuardRules, AssetError> {
    val decoder = AssetDecoder(GuardsFormat.FILE)
    val root = decoder.root(json)
    decoder.members(root, TopLevelKeys)
    decoder.members(root["empty"], emptySet())
    val rules = GuardRules(
        version = decoder.int(root["version"]),
        order = decodeOrder(decoder, root["order"]),
        replyExtraction = decodeReplyExtraction(decoder, root["reply_extraction"]),
        normalisation = decodeNormalisation(decoder, root["normalisation"]),
        assistantPhrasing = decodeAssistantPhrasing(decoder, root["assistant_phrasing"]),
        length = decodeLength(decoder, root["length"]),
        unspokenWords = decodeUnspokenWords(decoder, root["unspoken_words"]),
        echo = decodeEcho(decoder, root["echo"]),
        snippetMarkers = decodeSnippetMarkers(decoder, root["snippet_markers"]),
        fallback = decodeFallback(decoder, root["fallback"]),
    )
    return decoder.finish(rules)
}

private fun decodeOrder(decoder: AssetDecoder, node: JsonNode): List<Guard> {
    val ids = decoder.strings(node)
    val guards = ids.mapNotNull { id -> Guard.entries.firstOrNull { it.id == id } }
    decoder.expect(guards.size == ids.size) { "order names an unknown guard: $ids" }
    decoder.expect(guards.toSet().size == guards.size) { "order lists a guard twice: $ids" }
    return guards
}

private fun decodeReplyExtraction(decoder: AssetDecoder, node: JsonNode): GuardRules.ReplyExtraction {
    decoder.members(node, setOf("strip_leading_think_block", "unescape_entities", "wrapping_quote_pairs"))
    val think = node["strip_leading_think_block"]
    decoder.members(think, setOf("open", "close"))
    val entities = decoder.pairs(node["unescape_entities"])
    decoder.expect(entities.map { it.first }.toSet().size == entities.size) { "unescape_entities repeats an entity" }
    return GuardRules.ReplyExtraction(
        thinkOpen = decoder.string(think["open"]).also { decoder.expect(it.isNotEmpty()) { "think open is empty" } },
        thinkClose = decoder.string(think["close"]).also { decoder.expect(it.isNotEmpty()) { "think close is empty" } },
        unescapeEntities = entities,
        wrappingQuotePairs = decoder.pairs(node["wrapping_quote_pairs"]),
    )
}

private fun decodeNormalisation(decoder: AssetDecoder, node: JsonNode): GuardRules.Normalisation {
    decoder.members(
        node,
        setOf(
            "unicode_form",
            "apostrophes",
            "number_separators",
            "ordinal_suffixes",
            "list_marker_prefixes",
            "numbered_list_max_digits",
        ),
    )
    val form = decoder.string(node["unicode_form"])
    decoder.expect(normalizerForm(form) != null) { "normalisation.unicode_form '$form' is not NFC, NFD, NFKC or NFKD" }
    val maxDigits = decoder.int(node["numbered_list_max_digits"])
    decoder.expect(maxDigits >= 0) { "normalisation.numbered_list_max_digits must not be negative" }
    return GuardRules.Normalisation(
        unicodeForm = form,
        apostrophes = decoder.nonEmptyStrings(node["apostrophes"]),
        numberSeparators = decoder.string(node["number_separators"]),
        ordinalSuffixes = decoder.nonEmptyStrings(node["ordinal_suffixes"]),
        listMarkerPrefixes = decoder.nonEmptyStrings(node["list_marker_prefixes"]),
        numberedListMaxDigits = maxDigits,
    )
}

private fun decodeAssistantPhrasing(decoder: AssetDecoder, node: JsonNode): GuardRules.AssistantPhrasing {
    decoder.members(node, setOf("anywhere", "at_start"))
    return GuardRules.AssistantPhrasing(
        anywhere = decoder.nonEmptyStrings(node["anywhere"]),
        atStart = decoder.nonEmptyStrings(node["at_start"]),
    )
}

private fun decodeLength(decoder: AssetDecoder, node: JsonNode): GuardRules.Length {
    decoder.members(
        node,
        setOf("upper_ratio", "upper_margin_tokens", "lower_ratio", "lower_min_units", "fail_on_finish_reason"),
    )
    return GuardRules.Length(
        upperRatio = decoder.double(node["upper_ratio"]),
        upperMarginTokens = decoder.int(node["upper_margin_tokens"]),
        lowerRatio = decoder.double(node["lower_ratio"]),
        lowerMinUnits = decoder.int(node["lower_min_units"]),
        failOnFinishReason = decoder.strings(node["fail_on_finish_reason"]),
    )
}

private fun decodeEcho(decoder: AssetDecoder, node: JsonNode): GuardRules.Echo {
    decoder.members(node, setOf("ngram_size", "min_source_tokens", "ignore_ngrams_with_digits", "tag_names"))
    val ngramSize = decoder.int(node["ngram_size"])
    decoder.expect(ngramSize >= 1) { "echo.ngram_size must be at least 1" }
    return GuardRules.Echo(
        ngramSize = ngramSize,
        minSourceTokens = decoder.int(node["min_source_tokens"]),
        ignoreNgramsWithDigits = decoder.boolean(node["ignore_ngrams_with_digits"]),
        tagNames = decoder.nonEmptyStrings(node["tag_names"]),
    )
}

private fun decodeSnippetMarkers(decoder: AssetDecoder, node: JsonNode): GuardRules.SnippetMarkers {
    decoder.members(node, setOf("open", "prefix", "close", "max_digits", "regex"))
    val markers = GuardRules.SnippetMarkers(
        open = decoder.string(node["open"]),
        prefix = decoder.string(node["prefix"]),
        close = decoder.string(node["close"]),
        maxDigits = decoder.int(node["max_digits"]),
        regex = decoder.string(node["regex"]),
    )
    val parts = markers.open + markers.prefix + markers.close
    decoder.expect(markers.open.isNotEmpty() && markers.close.isNotEmpty() && markers.maxDigits >= 1) {
        "snippet_markers needs a non-empty open and close and max_digits >= 1"
    }
    decoder.expect(parts.none { it in GuardsFormat.REGEX_METACHARACTERS || it in '0'..'9' }) {
        "snippet_markers open, prefix and close must be plain characters"
    }
    val expected = "${markers.open}${markers.prefix}[0-9]{1,${markers.maxDigits}}${markers.close}"
    decoder.expect(markers.regex == expected) { "snippet_markers.regex does not match open, prefix, close, max_digits" }
    return markers
}

private fun decodeFallback(decoder: AssetDecoder, node: JsonNode): GuardRules.Fallback {
    decoder.members(
        node,
        setOf(
            "fillers",
            "edge_punctuation",
            "terminal_punctuation",
            "soft_final_punctuation",
            "pronoun_i_forms",
            "question_openers_wh",
            "question_openers_aux",
            "question_subjects",
        ),
    )
    return GuardRules.Fallback(
        fillers = decoder.strings(node["fillers"]),
        edgePunctuation = decoder.string(node["edge_punctuation"]),
        terminalPunctuation = decoder.string(node["terminal_punctuation"]),
        softFinalPunctuation = decoder.string(node["soft_final_punctuation"]),
        pronounIForms = decoder.strings(node["pronoun_i_forms"]),
        questionOpenersWh = decoder.strings(node["question_openers_wh"]),
        questionOpenersAux = decoder.strings(node["question_openers_aux"]),
        questionSubjects = decoder.strings(node["question_subjects"]),
    )
}
