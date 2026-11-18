package net.jbglenn.telltype.domain.cleanup

/* Typed list and map readers on top of AssetDecoder. */

/** The node's items as strings. */
internal fun AssetDecoder.strings(node: JsonNode): List<String> = items(node).map(::string)

/** The node's items as strings, none of them empty. */
internal fun AssetDecoder.nonEmptyStrings(node: JsonNode): List<String> = strings(node).also { values ->
    expect(values.none { it.isEmpty() }) { "${node.path} must not contain an empty string" }
}

/** The node's items as pairs of non-empty strings (`[["a", "b"], ...]`). */
internal fun AssetDecoder.pairs(node: JsonNode): List<Pair<String, String>> = items(node).map { item ->
    val parts = strings(item)
    expect(parts.size == 2 && parts.none { it.isEmpty() }) { "${item.path} must be two non-empty strings" }
    parts.getOrElse(0) { "" } to parts.getOrElse(1) { "" }
}

/** The node's members as a map of integers, in file order. */
internal fun AssetDecoder.longs(node: JsonNode): Map<String, Long> = members(node).associate { (key, value) ->
    key to long(value)
}
