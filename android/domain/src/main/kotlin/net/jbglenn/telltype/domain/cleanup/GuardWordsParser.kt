package net.jbglenn.telltype.domain.cleanup

/** `unspoken_words` of guards.json. */
internal fun decodeUnspokenWords(decoder: AssetDecoder, node: JsonNode): GuardRules.UnspokenWords {
    decoder.members(
        node,
        setOf(
            "allowance_ratio",
            "ignore_single_character_tokens",
            "free_words",
            "equivalents",
            "contractions",
            "number_words",
        ),
    )
    val equivalents = decoder.items(node["equivalents"]).map { entry ->
        val sides = decoder.items(entry).map { decoder.nonEmptyStrings(it) }
        decoder.expect(sides.size == 2) { "${entry.path} must be a pair of word lists" }
        sides.getOrElse(0) { emptyList() } to sides.getOrElse(1) { emptyList() }
    }
    return GuardRules.UnspokenWords(
        allowanceRatio = decoder.double(node["allowance_ratio"]),
        ignoreSingleCharacterTokens = decoder.boolean(node["ignore_single_character_tokens"]),
        freeWords = decoder.strings(node["free_words"]),
        equivalents = equivalents,
        contractions = decodeContractions(decoder, node["contractions"]),
        numberWords = decodeNumberWords(decoder, node["number_words"]),
    )
}

private fun decodeContractions(decoder: AssetDecoder, node: JsonNode): GuardRules.Contractions {
    decoder.members(node, setOf("suffixes", "possessive_suffix", "stem_overrides"))
    val suffixes = decoder.members(node["suffixes"]).associate { (suffix, expansions) ->
        decoder.expect(suffix.isNotEmpty()) { "${node.path}.suffixes has an empty suffix" }
        suffix to decoder.nonEmptyStrings(expansions)
    }
    return GuardRules.Contractions(
        suffixes = suffixes,
        possessiveSuffix = decoder.string(node["possessive_suffix"]),
        stemOverrides = decoder.members(node["stem_overrides"]).associate { (stem, to) -> stem to decoder.string(to) },
    )
}

private fun decodeNumberWords(decoder: AssetDecoder, node: JsonNode): GuardRules.NumberWords {
    decoder.members(node, setOf("units", "teens", "tens", "scales", "months", "connectors", "max_run_words"))
    val maxRunWords = decoder.int(node["max_run_words"])
    decoder.expect(maxRunWords >= 0) { "${node.path}.max_run_words must not be negative" }
    return GuardRules.NumberWords(
        units = decoder.longs(node["units"]),
        teens = decoder.longs(node["teens"]),
        tens = decoder.longs(node["tens"]),
        scales = decoder.longs(node["scales"]),
        months = decoder.longs(node["months"]),
        connectors = decoder.strings(node["connectors"]),
        maxRunWords = maxRunWords,
    )
}
