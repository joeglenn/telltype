package net.jbglenn.telltype.testing

import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import net.jbglenn.telltype.core.common.Clock
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
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.domain.port.AppRuleRepository
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SnippetRepository

/*
 * In-memory repositories that follow the port contracts (ordering, "delete of a missing row succeeds", "update
 * of a missing row is StorageFailed"). Each has `faults` to make every operation fail or wait.
 */

/** Replaces the element matching [key] or reports that none exists. */
private inline fun <T> MutableStateFlow<List<T>>.replaceExisting(item: T, key: (T) -> Any): Boolean {
    val current = value
    if (current.none { key(it) == key(item) }) return false
    value = current.map { if (key(it) == key(item)) item else it }
    return true
}

/** Flattens a nested outcome from a fault-guarded operation whose body can fail itself. */
private fun <T> Outcome<Outcome<T, StorageError>, StorageError>.flatten(): Outcome<T, StorageError> = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> this
}

/** Settings fake, starting from [initial]. */
public class FakeSettingsRepository(initial: Settings = Settings.Defaults) : SettingsRepository {
    private val state = MutableStateFlow(initial)

    /** Failure and delay switches. */
    public val faults: StorageFaults = StorageFaults()

    override val settings: Flow<Settings> = state

    override suspend fun current(): Settings = state.value

    override suspend fun update(transform: (Settings) -> Settings): Outcome<Settings, StorageError> =
        faults.run { transform(state.value).also { state.value = it } }
}

/** History fake. */
@Suppress("TooManyFunctions") // One function per History query; the Archive (D52) adds its own.
public class FakeHistoryRepository : HistoryRepository {
    private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Failure and delay switches. */
    public val faults: StorageFaults = StorageFaults()

    /** Every stored entry, in insertion order. */
    public val entries: List<HistoryEntry> get() = state.value

    override fun recent(limit: Int): Flow<List<HistoryEntry>> =
        state.map { all -> all.sortedByDescending { it.createdAt }.take(limit) }

    override fun search(query: String, limit: Int): Flow<List<HistoryEntry>> = state.map { all ->
        all.filter {
            it.finalText.contains(query, ignoreCase = true) ||
                it.rawTranscript.contains(query, ignoreCase = true)
        }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    override suspend fun get(id: SessionId): Outcome<HistoryEntry?, StorageError> =
        faults.run { state.value.firstOrNull { it.id == id } }

    override suspend fun upsert(entry: HistoryEntry): Outcome<Unit, StorageError> = faults.run {
        if (!state.replaceExisting(entry) { it.id }) state.value += entry
    }

    override suspend fun delete(id: SessionId): Outcome<Unit, StorageError> = faults.run {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun deleteOlderThan(cutoff: Instant): Outcome<Int, StorageError> = faults.run {
        val (old, kept) = state.value.partition { it.createdAt < cutoff && !it.archived }
        state.value = kept
        old.size
    }

    override fun count(): Flow<Int> = state.map { it.size }

    override suspend fun deleteAll(): Outcome<Int, StorageError> = faults.run {
        val deleted = state.value.size
        state.value = emptyList()
        deleted
    }

    override fun list(archived: Boolean, limit: Int): Flow<List<HistoryEntry>> =
        recent(Int.MAX_VALUE).map { all -> all.filter { it.archived == archived }.take(limit) }

    override fun search(query: String, archived: Boolean, limit: Int): Flow<List<HistoryEntry>> =
        search(query, Int.MAX_VALUE).map { all -> all.filter { it.archived == archived }.take(limit) }

    override suspend fun setArchived(id: SessionId, archived: Boolean): Outcome<Unit, StorageError> = faults.run {
        state.value = state.value.map { if (it.id == id) it.copy(archived = archived) else it }
    }

    override fun archivedCount(): Flow<Int> = state.map { all -> all.count { it.archived } }

    override suspend fun deleteAll(includeArchived: Boolean): Outcome<Int, StorageError> = faults.run {
        val (gone, kept) = state.value.partition { includeArchived || !it.archived }
        state.value = kept
        gone.size
    }
}

/** Dictionary fake; ids count from 1 and creation times come from [clock]. */
public class FakeDictionaryRepository(private val clock: Clock = FixedClock()) : DictionaryRepository {
    private val termState = MutableStateFlow<List<DictionaryTerm>>(emptyList())
    private val replacementState = MutableStateFlow<List<Replacement>>(emptyList())
    private var nextId = 1L

    /** Failure and delay switches. */
    public val faults: StorageFaults = StorageFaults()

    override val terms: Flow<List<DictionaryTerm>> = termState.map { all -> all.sortedBy { it.term.lowercase() } }

    override val replacements: Flow<List<Replacement>> =
        replacementState.map { all -> all.sortedBy { it.heard.lowercase() } }

    override suspend fun addTerm(
        term: String,
        soundsLike: List<String>,
        source: EntrySource,
    ): Outcome<DictionaryTerm, StorageError> = faults.run {
        DictionaryTerm(DictionaryTermId(nextId++), term, soundsLike, source, uses = 0, createdAt = clock.now())
            .also { termState.value += it }
    }

    override suspend fun updateTerm(term: DictionaryTerm): Outcome<Unit, StorageError> = faults.run {
        if (termState.replaceExisting(term) { it.id }) Outcome.Ok(Unit) else Outcome.Err(StorageFailed)
    }.flatten()

    override suspend fun deleteTerm(id: DictionaryTermId): Outcome<Unit, StorageError> = faults.run {
        termState.value = termState.value.filterNot { it.id == id }
    }

    override suspend fun addReplacement(
        heard: String,
        replaceWith: String,
        source: EntrySource,
    ): Outcome<Replacement, StorageError> = faults.run {
        Replacement(ReplacementId(nextId++), heard, replaceWith, source, uses = 0, createdAt = clock.now())
            .also { replacementState.value += it }
    }

    override suspend fun updateReplacement(replacement: Replacement): Outcome<Unit, StorageError> = faults.run {
        if (replacementState.replaceExisting(replacement) { it.id }) Outcome.Ok(Unit) else Outcome.Err(StorageFailed)
    }.flatten()

    override suspend fun deleteReplacement(id: ReplacementId): Outcome<Unit, StorageError> = faults.run {
        replacementState.value = replacementState.value.filterNot { it.id == id }
    }

    override suspend fun recordUses(
        terms: Set<DictionaryTermId>,
        replacements: Set<ReplacementId>,
    ): Outcome<Unit, StorageError> = faults.run {
        termState.value = termState.value.map { if (it.id in terms) it.copy(uses = it.uses + 1) else it }
        replacementState.value =
            replacementState.value.map { if (it.id in replacements) it.copy(uses = it.uses + 1) else it }
    }
}

/** Snippet fake; ids count from 1 and creation times come from [clock]. */
public class FakeSnippetRepository(private val clock: Clock = FixedClock()) : SnippetRepository {
    private val state = MutableStateFlow<List<Snippet>>(emptyList())
    private var nextId = 1L

    /** Failure and delay switches. */
    public val faults: StorageFaults = StorageFaults()

    override val snippets: Flow<List<Snippet>> = state.map { all -> all.sortedBy { it.trigger.lowercase() } }

    override suspend fun add(trigger: String, text: String): Outcome<Snippet, StorageError> = faults.run {
        Snippet(SnippetId(nextId++), trigger, text, uses = 0, createdAt = clock.now()).also { state.value += it }
    }

    override suspend fun update(snippet: Snippet): Outcome<Unit, StorageError> = faults.run {
        if (state.replaceExisting(snippet) { it.id }) Outcome.Ok(Unit) else Outcome.Err(StorageFailed)
    }.flatten()

    override suspend fun delete(id: SnippetId): Outcome<Unit, StorageError> = faults.run {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun recordUse(id: SnippetId): Outcome<Unit, StorageError> = faults.run {
        state.value = state.value.map { if (it.id == id) it.copy(uses = it.uses + 1) else it }
    }
}

/** App rule fake. */
public class FakeAppRuleRepository : AppRuleRepository {
    private val state = MutableStateFlow<List<AppRule>>(emptyList())

    /** Failure and delay switches. */
    public val faults: StorageFaults = StorageFaults()

    override val rules: Flow<List<AppRule>> = state.map { all -> all.sortedBy { it.app.name } }

    override suspend fun find(app: AppPackage): Outcome<AppRule?, StorageError> =
        faults.run { state.value.firstOrNull { it.app == app } }

    override suspend fun upsert(rule: AppRule): Outcome<Unit, StorageError> = faults.run {
        if (!state.replaceExisting(rule) { it.app }) state.value += rule
    }

    override suspend fun delete(app: AppPackage): Outcome<Unit, StorageError> = faults.run {
        state.value = state.value.filterNot { it.app == app }
    }
}

/** Pending job fake. */
public class FakePendingJobRepository : PendingJobRepository {
    private val state = MutableStateFlow<List<PendingJob>>(emptyList())

    /** Failure and delay switches. */
    public val faults: StorageFaults = StorageFaults()

    override val jobs: Flow<List<PendingJob>> = state.map { all -> all.sortedBy { it.createdAt } }

    override suspend fun enqueue(job: PendingJob): Outcome<Unit, StorageError> = faults.run {
        if (!state.replaceExisting(job) { it.recording }) state.value += job
    }

    override suspend fun recordFailure(recording: RecordingId, errorCode: String): Outcome<Unit, StorageError> =
        faults.run {
            val job = state.value.firstOrNull { it.recording == recording }
                ?: return@run Outcome.Err(StorageFailed)
            state.replaceExisting(job.copy(attempts = job.attempts + 1, lastErrorCode = errorCode)) { it.recording }
            Outcome.Ok(Unit)
        }.flatten()

    override suspend fun remove(recording: RecordingId): Outcome<Unit, StorageError> = faults.run {
        state.value = state.value.filterNot { it.recording == recording }
    }
}
