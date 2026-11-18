package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.cleanup.LengthViolation

/** Guard 3 (GUARDS.md): cut off, or far longer or shorter than the transcript. */
internal fun guardLength(subject: GuardSubject, rules: GuardRules): GuardVerdict.Rejected? {
    val length = rules.length
    val replyCount = subject.replyTokens.size
    val spokenCount = subject.transcriptTokens.size
    val upper = maxOf(length.upperRatio * spokenCount, (spokenCount + length.upperMarginTokens).toDouble())
    val units = transcriptUnits(subject.transcriptTokens, rules)
    val lower = length.lowerRatio * units
    return when {
        subject.finishReason != null && subject.finishReason in length.failOnFinishReason ->
            GuardVerdict.LengthOutOfBounds(LengthViolation.CUT_OFF, replyCount, null)

        replyCount > upper -> GuardVerdict.LengthOutOfBounds(LengthViolation.TOO_LONG, replyCount, upper)

        units >= length.lowerMinUnits && replyCount < lower ->
            GuardVerdict.LengthOutOfBounds(LengthViolation.TOO_SHORT, replyCount, lower)

        else -> null
    }
}

/**
 * Transcript size for the lower length bound: fillers dropped, each number-word run counts once; a connector
 * between two number words belongs to the run (GUARDS.md guard 3).
 */
internal fun transcriptUnits(tokens: List<Token>, rules: GuardRules): Int {
    val table = rules.unspokenWords.numberWords
    val kept = tokens.filterNot { it.kind == TokenKind.WORD && it.text in rules.fallback.fillers }
    fun isNumberWordAt(index: Int): Boolean = kept.getOrNull(index)?.let {
        it.kind == TokenKind.WORD && isNumberWord(it.text, table)
    } == true
    var units = 0
    var inRun = false
    kept.indices.forEach { index ->
        val token = kept[index]
        val connector = token.kind == TokenKind.WORD && token.text in table.connectors
        val bridges = inRun && connector && isNumberWordAt(index + 1)
        when {
            isNumberWordAt(index) -> {
                if (!inRun) units++
                inRun = true
            }

            bridges -> Unit

            else -> {
                inRun = false
                units++
            }
        }
    }
    return units
}
