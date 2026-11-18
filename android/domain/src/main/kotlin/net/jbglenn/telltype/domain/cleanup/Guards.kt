package net.jbglenn.telltype.domain.cleanup

import kotlin.math.floor
import net.jbglenn.telltype.core.model.cleanup.EchoSource
import net.jbglenn.telltype.core.model.cleanup.Guard
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict

/** Runs the guards of [GuardRules.order]; the first failure decides (GUARDS.md section 4). */
internal fun checkGuards(subject: GuardSubject, rules: GuardRules): GuardVerdict =
    rules.order.firstNotNullOfOrNull { runGuard(it, subject, rules) } ?: GuardVerdict.Accepted(subject.reply)

/** Runs one guard: its rejection, or null when it passes. */
internal fun runGuard(guard: Guard, subject: GuardSubject, rules: GuardRules): GuardVerdict.Rejected? = when (guard) {
    Guard.EMPTY -> guardEmpty(subject)
    Guard.ASSISTANT_PHRASING -> guardAssistantPhrasing(subject, rules)
    Guard.LENGTH -> guardLength(subject, rules)
    Guard.UNSPOKEN_WORDS -> guardUnspokenWords(subject, rules)
    Guard.ECHO -> guardEcho(subject, rules)
    Guard.SNIPPET_MARKERS -> guardSnippetMarkers(subject, rules)
}

private fun guardEmpty(subject: GuardSubject): GuardVerdict.Rejected? {
    val blank = pythonStrip(subject.reply).isEmpty()
    val noTokens = subject.replyTokens.isEmpty() && subject.transcriptTokens.isNotEmpty()
    return if (blank || noTokens) GuardVerdict.EmptyReply else null
}

private fun guardAssistantPhrasing(subject: GuardSubject, rules: GuardRules): GuardVerdict.Rejected? {
    val reply = textsOf(subject.replyTokens)
    val transcript = textsOf(subject.transcriptTokens)
    val phrasing = rules.assistantPhrasing
    val anywhere = phrasing.anywhere.firstOrNull { phrase ->
        val needle = textsOf(tokensOf(phrase, rules))
        containsSequence(reply, needle) && !containsSequence(transcript, needle)
    }
    val atStart = phrasing.atStart.firstOrNull { phrase ->
        val needle = textsOf(tokensOf(phrase, rules))
        reply.take(needle.size) == needle && !containsSequence(transcript, needle)
    }
    return (anywhere ?: atStart)?.let(GuardVerdict::AssistantPhrasing)
}

private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean =
    needle.isNotEmpty() && needle.size <= haystack.size &&
        (0..haystack.size - needle.size).any { haystack.subList(it, it + needle.size) == needle }

private fun guardUnspokenWords(subject: GuardSubject, rules: GuardRules): GuardVerdict.Rejected? {
    val unspoken = unspokenTokens(subject, rules)
    val allowance = floor(rules.unspokenWords.allowanceRatio * subject.replyTokens.size).toInt()
    return if (unspoken.size > allowance) GuardVerdict.UnspokenWords(unspoken, allowance) else null
}

private fun guardEcho(subject: GuardSubject, rules: GuardRules): GuardVerdict.Rejected? {
    val echo = rules.echo
    val replyLower = normaliseForMatch(subject.reply, rules.normalisation)
    val transcriptLower = normaliseForMatch(subject.transcript, rules.normalisation)
    val tagEchoed = echo.tagNames.flatMap { listOf("<$it", "</$it") }.any { tag ->
        tag in replyLower && tag !in transcriptLower
    }
    val sources = listOf(
        EchoSource.SYSTEM_PROMPT to subject.inputs.systemPrompt,
        EchoSource.TONE_LINE to subject.inputs.toneLine,
        EchoSource.DICTIONARY to subject.dictionary.joinToString(", "),
        EchoSource.CONTEXT_BEFORE to subject.contextBefore,
    )
    val source = if (tagEchoed) {
        EchoSource.PROMPT_TAG
    } else {
        sources.firstOrNull { (_, text) -> copiesSource(subject, tokensOf(text, rules), echo) }?.first
    }
    return source?.let(GuardVerdict::Echo)
}

private fun copiesSource(subject: GuardSubject, source: List<Token>, echo: GuardRules.Echo): Boolean {
    if (source.size < echo.minSourceTokens) return false
    val size = minOf(echo.ngramSize, source.size)
    val skipDigits = echo.ignoreNgramsWithDigits
    val echoed = grams(subject.replyTokens, size, skipDigits) intersect grams(source, size, skipDigits)
    return (echoed - grams(subject.transcriptTokens, size, skipDigits)).isNotEmpty()
}

private fun grams(tokens: List<Token>, size: Int, skipDigits: Boolean): Set<List<String>> =
    (0..tokens.size - size).map { tokens.subList(it, it + size) }
        .filterNot { window -> skipDigits && window.any { it.kind == TokenKind.NUMBER } }
        .map(::textsOf)
        .toSet()

private fun guardSnippetMarkers(subject: GuardSubject, rules: GuardRules): GuardVerdict.Rejected? {
    val sent = countSnippetMarkers(subject.transcript, rules.snippetMarkers)
    val got = countSnippetMarkers(subject.reply, rules.snippetMarkers)
    return if (sent != got) GuardVerdict.SnippetMarkersChanged(sent, got) else null
}
