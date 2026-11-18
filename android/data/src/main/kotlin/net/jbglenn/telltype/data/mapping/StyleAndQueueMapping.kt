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

/** [Snippet] to its row. */
internal fun Snippet.toEntity(): SnippetEntity = SnippetEntity(
    id = id.value,
    trigger = trigger,
    text = text,
    uses = uses,
    createdAt = createdAt.toEpochMillis(),
)

/** A row to its [Snippet]. */
internal fun SnippetEntity.toModel(): Snippet = Snippet(
    id = SnippetId(id),
    trigger = trigger,
    text = text,
    uses = uses.coerceAtLeast(0),
    createdAt = instantOf(createdAt),
)

/** [AppRule] to its row. */
internal fun AppRule.toEntity(): AppRuleEntity = AppRuleEntity(
    appPackage = app.name,
    categoryOverride = categoryOverride?.id,
    toneOverride = toneOverride?.id,
    excluded = excluded,
)

/** A row to its [AppRule], or null when unreadable. */
internal fun AppRuleEntity.toModel(): AppRule? = AppRule(
    app = AppPackage(appPackage),
    categoryOverride = categoryOverride?.let {
        enumById(AppCategory.entries, it) { category -> category.id }
            ?: return null
    },
    toneOverride = toneOverride?.let { enumById(Tone.entries, it) { tone -> tone.id } ?: return null },
    excluded = excluded,
)

/** [PendingJob] to its row. */
internal fun PendingJob.toEntity(): PendingJobEntity = PendingJobEntity(
    id = recording.value,
    sessionId = session.value,
    createdAt = createdAt.toEpochMillis(),
    audioPath = audioPath,
    category = category.id,
    tone = tone.id,
    attempts = attempts,
    lastError = lastErrorCode,
    appPackage = app?.name,
    incognito = incognito,
)

/** A row to its [PendingJob], or null when unreadable. */
internal fun PendingJobEntity.toModel(): PendingJob? = PendingJob(
    recording = RecordingId(id),
    session = SessionId(sessionId),
    createdAt = instantOf(createdAt),
    audioPath = audioPath,
    category = enumById(AppCategory.entries, category) { it.id } ?: return null,
    tone = enumById(Tone.entries, tone) { it.id } ?: return null,
    attempts = attempts.coerceAtLeast(0),
    lastErrorCode = lastError,
    app = appPackage?.let(::AppPackage),
    incognito = incognito,
)
