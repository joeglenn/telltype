package net.jbglenn.telltype.domain.text

import java.util.Locale
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId

/**
 * The exact replacements a dictionary's "sounds like" forms stand for (docs/PLAN.md section 4.5: the dictionary's
 * third use, the final replacement of known wrong-to-right pairs). Each form of a term is replaced by the term, as
 * written and in lower case (a lower-case form also matches capitalised at a sentence start, see
 * [applyReplacements]). Blank forms and forms equal to the term are left out. The ids are negative
 * ([isSoundsLikeReplacement]): they are not stored replacements, so no stored use count may change for them.
 */
internal fun soundsLikeReplacements(terms: List<DictionaryTerm>): List<Replacement> = terms.flatMap { term ->
    val id = ReplacementId(-1 - term.id.value)
    term.soundsLike.map(String::trim)
        .flatMap { listOf(it, it.lowercase(Locale.ROOT)) }
        .filter { it.isNotEmpty() && it != term.term }
        .distinct()
        .map { heard -> Replacement(id, heard, term.term, term.source, uses = 0, createdAt = term.createdAt) }
}

/** Whether [id] belongs to a replacement made by [soundsLikeReplacements] rather than a stored one. */
internal fun isSoundsLikeReplacement(id: ReplacementId): Boolean = id.value < 0
