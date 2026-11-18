package net.jbglenn.telltype.domain.cleanup

/**
 * Reply extraction (`shared/GUARDS.md` section 2) on the message content: null becomes `""`, line endings
 * normalised, a leading think block dropped, entities unescaped in one pass, trimmed, and one pair of wrapping
 * quotes removed unless [sentInput] (trimmed) starts with the same opening quote.
 */
internal fun extractReplyText(content: String?, sentInput: String, rules: GuardRules.ReplyExtraction): String {
    if (content == null) return ""
    val normalised = content.replace("\r\n", "\n").replace("\r", "\n")
    val leading = pythonStripStart(normalised)
    val withoutThink = when {
        !leading.startsWith(rules.thinkOpen) -> normalised

        else -> leading.indexOf(rules.thinkClose).let { end ->
            if (end < 0) "" else leading.substring(end + rules.thinkClose.length)
        }
    }
    val text = pythonStrip(unescapeEntities(withoutThink, rules.unescapeEntities))
    val spokenStart = pythonStrip(sentInput)
    val quotes = rules.wrappingQuotePairs.firstOrNull { (opening, closing) ->
        val wrapped = text.length >= opening.length + closing.length &&
            text.startsWith(opening) &&
            text.endsWith(closing)
        wrapped && !spokenStart.startsWith(opening)
    }
    return quotes?.let { (opening, closing) ->
        pythonStrip(text.substring(opening.length, text.length - closing.length))
    } ?: text
}

/** One left-to-right pass replacing each entity (first match in list order wins at a position) by its text. */
internal fun unescapeEntities(text: String, entities: List<Pair<String, String>>): String {
    val result = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val entity = entities.firstOrNull { (name, _) -> name.isNotEmpty() && text.startsWith(name, index) }
        if (entity == null) {
            result.append(text[index])
            index++
        } else {
            result.append(entity.second)
            index += entity.first.length
        }
    }
    return result.toString()
}
