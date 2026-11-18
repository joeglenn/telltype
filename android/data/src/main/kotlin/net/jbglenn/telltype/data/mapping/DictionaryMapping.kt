package net.jbglenn.telltype.data.mapping

import kotlin.time.Duration.Companion.milliseconds
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.data.db.AppRuleEntity
import net.jbglenn.telltype.data.db.DictionaryTermEntity
import net.jbglenn.telltype.data.db.HistoryEntity
import net.jbglenn.telltype.data.db.PendingJobEntity
import net.jbglenn.telltype.data.db.ReplacementEntity
import net.jbglenn.telltype.data.db.SnippetEntity

/*
 * Explicit mappers between Room rows and domain models. `toEntity` is total; `toModel` returns null for a row it
 * cannot read (an unknown enum id or bad JSON, which only a newer app version or a corrupted file could write),
 * and the repository reports or skips it.
 */

/** [DictionaryTerm] to its row. */
internal fun DictionaryTerm.toEntity(): DictionaryTermEntity = DictionaryTermEntity(
    id = id.value,
    term = term,
    soundsLike = encodeStringList(soundsLike),
    source = source.id,
    uses = uses,
    createdAt = createdAt.toEpochMillis(),
)

/** A row to its [DictionaryTerm], or null when unreadable. */
internal fun DictionaryTermEntity.toModel(): DictionaryTerm? = DictionaryTerm(
    id = DictionaryTermId(id),
    term = term,
    soundsLike = decodeStringList(soundsLike) ?: return null,
    source = enumById(EntrySource.entries, source) { it.id } ?: return null,
    uses = uses.coerceAtLeast(0),
    createdAt = instantOf(createdAt),
)

/** [Replacement] to its row. */
internal fun Replacement.toEntity(): ReplacementEntity = ReplacementEntity(
    id = id.value,
    heard = heard,
    replaceWith = replaceWith,
    source = source.id,
    uses = uses,
    createdAt = createdAt.toEpochMillis(),
)

/** A row to its [Replacement], or null when unreadable. */
internal fun ReplacementEntity.toModel(): Replacement? = Replacement(
    id = ReplacementId(id),
    heard = heard,
    replaceWith = replaceWith,
    source = enumById(EntrySource.entries, source) { it.id } ?: return null,
    uses = uses.coerceAtLeast(0),
    createdAt = instantOf(createdAt),
)
