package net.jbglenn.telltype.domain.port

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppRule

/*
 * Repositories: the one place for each kind of stored data (docs/PLAN.md section 6.3, Appendix E). All are
 * implemented by `:data` (Room, DataStore) and run their I/O on the io dispatcher. Common rules:
 * - Flows emit the current content at once and again after every change.
 * - Deleting something that does not exist succeeds; updating something that does not exist is `StorageFailed`.
 * - Creation time stamps come from the injected `Clock`.
 */

/** User settings in DataStore; the server key is encrypted with a Keystore key. */
public interface SettingsRepository {
    /** The settings; [Settings.Defaults] before anything was saved. */
    public val settings: Flow<Settings>

    /** The settings now. */
    public suspend fun current(): Settings

    /** Applies [transform] atomically to the stored settings and returns the result. */
    public suspend fun update(transform: (Settings) -> Settings): Outcome<Settings, StorageError>
}

/** The `history` table. Incognito sessions never reach it; the daily job enforces retention. */
@Suppress("TooManyFunctions") // One function per History query; the Archive (D52) adds its own.
public interface HistoryRepository {
    /** The newest [limit] entries, newest first. */
    public fun recent(limit: Int): Flow<List<HistoryEntry>>

    /**
     * Entries whose final text or raw transcript contains [query] (case-insensitive), newest first, at most
     * [limit].
     */
    public fun search(query: String, limit: Int): Flow<List<HistoryEntry>>

    /** One entry, or null when there is none with [id]. */
    public suspend fun get(id: SessionId): Outcome<HistoryEntry?, StorageError>

    /** Inserts [entry] or replaces the entry with the same id. */
    public suspend fun upsert(entry: HistoryEntry): Outcome<Unit, StorageError>

    /** Deletes one entry. */
    public suspend fun delete(id: SessionId): Outcome<Unit, StorageError>

    /** Deletes entries created before [cutoff], except archived ones (D52); returns how many. */
    public suspend fun deleteOlderThan(cutoff: Instant): Outcome<Int, StorageError>

    /** How many entries there are; follows every change. */
    public fun count(): Flow<Int>

    /**
     * Deletes every entry ("Clear history"); returns how many. Implementations leave none of the deleted text
     * readable in the store's files.
     */
    public suspend fun deleteAll(): Outcome<Int, StorageError>

    /** The newest [limit] entries of History ([archived] false) or of the Archive ([archived] true), newest first. */
    public fun list(archived: Boolean, limit: Int): Flow<List<HistoryEntry>>

    /** Like [search], within History ([archived] false) or within the Archive ([archived] true) only. */
    public fun search(query: String, archived: Boolean, limit: Int): Flow<List<HistoryEntry>>

    /** Moves one entry to the Archive or back to History (D52); an unknown id changes nothing. */
    public suspend fun setArchived(id: SessionId, archived: Boolean): Outcome<Unit, StorageError>

    /** How many entries are in the Archive; follows every change. */
    public fun archivedCount(): Flow<Int>

    /**
     * Deletes every entry of History and, when [includeArchived], of the Archive too; returns how many. Leaves none
     * of the deleted text readable, like [deleteAll].
     */
    public suspend fun deleteAll(includeArchived: Boolean): Outcome<Int, StorageError>
}

/** The `dictionary_term` and `replacement` tables. */
public interface DictionaryRepository {
    /** All terms, alphabetical. */
    public val terms: Flow<List<DictionaryTerm>>

    /** All replacements, alphabetical by [Replacement.heard]. */
    public val replacements: Flow<List<Replacement>>

    /** Adds a term with zero uses and returns it with its new id. */
    public suspend fun addTerm(
        term: String,
        soundsLike: List<String>,
        source: EntrySource,
    ): Outcome<DictionaryTerm, StorageError>

    /** Replaces the stored term that has [DictionaryTerm.id]. */
    public suspend fun updateTerm(term: DictionaryTerm): Outcome<Unit, StorageError>

    /** Deletes a term. */
    public suspend fun deleteTerm(id: DictionaryTermId): Outcome<Unit, StorageError>

    /** Adds a replacement with zero uses and returns it with its new id. */
    public suspend fun addReplacement(
        heard: String,
        replaceWith: String,
        source: EntrySource,
    ): Outcome<Replacement, StorageError>

    /** Replaces the stored replacement that has [Replacement.id]. */
    public suspend fun updateReplacement(replacement: Replacement): Outcome<Unit, StorageError>

    /** Deletes a replacement. */
    public suspend fun deleteReplacement(id: ReplacementId): Outcome<Unit, StorageError>

    /** Adds one use to each listed term and replacement (one dictation used them). Unknown ids are ignored. */
    public suspend fun recordUses(
        terms: Set<DictionaryTermId>,
        replacements: Set<ReplacementId>,
    ): Outcome<Unit, StorageError>
}

/** The `snippet` table. */
public interface SnippetRepository {
    /** All snippets, alphabetical by trigger. */
    public val snippets: Flow<List<Snippet>>

    /** Adds a snippet with zero uses and returns it with its new id. */
    public suspend fun add(trigger: String, text: String): Outcome<Snippet, StorageError>

    /** Replaces the stored snippet that has [Snippet.id]. */
    public suspend fun update(snippet: Snippet): Outcome<Unit, StorageError>

    /** Deletes a snippet. */
    public suspend fun delete(id: SnippetId): Outcome<Unit, StorageError>

    /** Adds one use. Unknown ids are ignored. */
    public suspend fun recordUse(id: SnippetId): Outcome<Unit, StorageError>
}

/** The `app_rule` table: per-app category and tone overrides, and the exclusion list (seeded from Appendix J). */
public interface AppRuleRepository {
    /** All rules, by package name. */
    public val rules: Flow<List<AppRule>>

    /** The rule for [app], or null when it has none. */
    public suspend fun find(app: AppPackage): Outcome<AppRule?, StorageError>

    /** Inserts or replaces the rule for [AppRule.app]. */
    public suspend fun upsert(rule: AppRule): Outcome<Unit, StorageError>

    /** Deletes the rule for [app]. */
    public suspend fun delete(app: AppPackage): Outcome<Unit, StorageError>
}

/** The `pending_job` table: recordings waiting for the server (section 8.5). */
public interface PendingJobRepository {
    /** All jobs, oldest first. */
    public val jobs: Flow<List<PendingJob>>

    /** Stores [job]; a job for the same recording is replaced. */
    public suspend fun enqueue(job: PendingJob): Outcome<Unit, StorageError>

    /** Adds one failed attempt and records [errorCode] as the last error. */
    public suspend fun recordFailure(recording: RecordingId, errorCode: String): Outcome<Unit, StorageError>

    /** Removes the job (after success, or when the user discards it). */
    public suspend fun remove(recording: RecordingId): Outcome<Unit, StorageError>
}
