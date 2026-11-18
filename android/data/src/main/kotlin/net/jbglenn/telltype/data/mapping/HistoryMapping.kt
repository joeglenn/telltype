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

/** [HistoryEntry] to its row. */
internal fun HistoryEntry.toEntity(): HistoryEntity = HistoryEntity(
    id = id.value,
    createdAt = createdAt.toEpochMillis(),
    appPackage = app.name,
    category = category.id,
    mode = mode.id,
    finalText = finalText,
    rawTranscript = rawTranscript,
    cleanupStatus = cleanupStatus?.id,
    insertedVia = insertedVia.id,
    audioMs = audioDuration.inWholeMilliseconds,
    timings = encodeTimings(timings),
    errorCode = errorCode,
    archived = archived,
)

/** A row to its [HistoryEntry], or null when unreadable. */
internal fun HistoryEntity.toModel(): HistoryEntry? = HistoryEntry(
    id = SessionId(id),
    createdAt = instantOf(createdAt),
    app = AppPackage(appPackage),
    category = enumById(AppCategory.entries, category) { it.id } ?: return null,
    mode = enumById(DictationMode.entries, mode) { it.id } ?: return null,
    finalText = finalText,
    rawTranscript = rawTranscript,
    cleanupStatus = cleanupStatus?.let { enumById(CleanupStatus.entries, it) { status -> status.id } ?: return null },
    insertedVia = enumById(InsertedVia.entries, insertedVia) { it.id } ?: return null,
    audioDuration = audioMs.takeIf { it >= 0 }?.milliseconds ?: return null,
    timings = decodeTimings(timings) ?: return null,
    errorCode = errorCode,
    archived = archived,
)
