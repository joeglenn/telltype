package net.jbglenn.telltype.domain.cleanup

import java.math.BigInteger

/** The tables of `unspoken_words.number_words` a word can belong to, in lookup order, plus the connector. */
private enum class NumberWordKind { UNITS, TEENS, TENS, SCALES, CONNECTOR }

/** One word of a number-word run: its table and value (0 for a connector). */
private data class NumberWord(val kind: NumberWordKind, val value: Long)

private object Cardinals {
    /**
     * The scale value that multiplies the running group instead of closing it (`shared/GUARDS.md` guard 4, cardinal
     * reading: "`hundred` → current = max(current,1) × 100; other scale → total += ..."). The value of the word
     * `hundred` itself comes from `guards.json`; this is the arithmetic rule of English cardinals.
     */
    const val MULTIPLYING_SCALE = 100L
}

/** A ten followed by one of these units is one atom in the digit-by-digit reading (`twenty six` = 26). */
private val JoinableUnits = 1L..9L

/** The table entry of [word] (units, then teens, tens, scales), or null when it is not a number word. */
private fun numberWordOf(word: String, table: GuardRules.NumberWords): NumberWord? {
    val kinds = listOf(
        NumberWordKind.UNITS to table.units,
        NumberWordKind.TEENS to table.teens,
        NumberWordKind.TENS to table.tens,
        NumberWordKind.SCALES to table.scales,
    )
    return kinds.firstNotNullOfOrNull { (kind, values) -> values[word]?.let { NumberWord(kind, it) } }
}

/** The value of [word] as a number word (not a month), or null. */
internal fun numberWordValue(word: String, table: GuardRules.NumberWords): Long? = numberWordOf(word, table)?.value

/** Whether [word] is in the units, teens, tens or scales table. */
internal fun isNumberWord(word: String, table: GuardRules.NumberWords): Boolean = numberWordOf(word, table) != null

private fun cardinal(run: List<NumberWord>): BigInteger {
    var total = BigInteger.ZERO
    var current = BigInteger.ZERO
    run.filter { it.kind != NumberWordKind.CONNECTOR }.forEach { word ->
        val value = BigInteger.valueOf(word.value)
        val group = if (current.signum() == 0) BigInteger.ONE else current
        when {
            word.kind != NumberWordKind.SCALES -> current += value

            word.value == Cardinals.MULTIPLYING_SCALE -> current = group * value

            else -> {
                total += group * value
                current = BigInteger.ZERO
            }
        }
    }
    return total + current
}

private fun concatenated(run: List<NumberWord>): String? {
    if (run.any { it.kind == NumberWordKind.SCALES || it.kind == NumberWordKind.CONNECTOR }) return null
    val atoms = StringBuilder()
    var index = 0
    while (index < run.size) {
        val word = run[index]
        val next = run.getOrNull(index + 1)
        val joinsUnit = word.kind == NumberWordKind.TENS && next != null && next.kind == NumberWordKind.UNITS &&
            next.value in JoinableUnits
        atoms.append(if (joinsUnit) word.value + next.value else word.value)
        index += if (joinsUnit) 2 else 1
    }
    return atoms.toString()
}

/** Maximal runs of number words; a connector after a number word joins the run; trailing connectors dropped. */
private fun numberWordRuns(tokens: List<Token>, table: GuardRules.NumberWords): List<List<NumberWord>> {
    val runs = mutableListOf<List<NumberWord>>()
    var current = mutableListOf<NumberWord>()
    tokens.forEach { token ->
        val entry = if (token.kind == TokenKind.WORD) numberWordOf(token.text, table) else null
        when {
            entry != null -> current += entry

            token.kind == TokenKind.WORD && token.text in table.connectors && current.isNotEmpty() ->
                current += NumberWord(NumberWordKind.CONNECTOR, 0)

            else -> {
                if (current.isNotEmpty()) runs += current
                current = mutableListOf()
            }
        }
    }
    if (current.isNotEmpty()) runs += current
    return runs.map { run -> run.dropLastWhile { it.kind == NumberWordKind.CONNECTOR } }
}

/** Every decimal string the transcript tokens can be read as containing (GUARDS.md guard 4, "Spoken numbers"). */
internal fun spokenNumberValues(tokens: List<Token>, rules: GuardRules): Set<String> {
    val table = rules.unspokenWords.numberWords
    val values = mutableSetOf<String>()
    tokens.forEach { token ->
        if (token.kind == TokenKind.NUMBER) {
            val groups = numberGroups(token.text, rules.normalisation)
            groups.mapTo(values, ::stripLeadingZeros)
            values += stripLeadingZeros(groups.joinToString(""))
        } else {
            table.months[token.text]?.let { values += it.toString() }
        }
    }
    numberWordRuns(tokens, table).forEach { run -> addRunReadings(run, table.maxRunWords, values) }
    return values
}

/** Both readings of every sub-span of [run] of at most [maxWords] words that neither starts nor ends with "and". */
private fun addRunReadings(run: List<NumberWord>, maxWords: Int, values: MutableSet<String>) {
    val spans = run.indices.flatMap { start ->
        (start + 1..minOf(run.size, start + maxWords)).map { end -> run.subList(start, end) }
    }
    spans.filter { it.first().kind != NumberWordKind.CONNECTOR && it.last().kind != NumberWordKind.CONNECTOR }
        .forEach { span ->
            values += cardinal(span).toString()
            concatenated(span)?.let { values += stripLeadingZeros(it) }
        }
}
