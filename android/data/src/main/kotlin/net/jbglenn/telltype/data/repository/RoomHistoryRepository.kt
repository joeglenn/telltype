package net.jbglenn.telltype.data.repository

import javax.inject.Inject
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.data.db.HistoryDao
import net.jbglenn.telltype.data.db.HistoryPragmas
import net.jbglenn.telltype.data.mapping.toEntity
import net.jbglenn.telltype.data.mapping.toEpochMillis
import net.jbglenn.telltype.data.mapping.toModel
import net.jbglenn.telltype.data.storage.StorageCalls
import net.jbglenn.telltype.domain.port.HistoryRepository

/** [HistoryRepository] on the Room `history` table. */
@Suppress("TooManyFunctions") // One function per History query; the Archive (D52) adds its own.
internal class RoomHistoryRepository @Inject constructor(
    private val dao: HistoryDao,
    dispatchers: DispatcherProvider,
    logger: Logger,
) : HistoryRepository {
    private val calls = StorageCalls(dispatchers, logger, LogTag("HistoryRepo"))

    override fun recent(limit: Int): Flow<List<HistoryEntry>> =
        calls.list("recent", dao.recent(limit.coerceAtLeast(0))) { it.toModel() }

    override fun search(query: String, limit: Int): Flow<List<HistoryEntry>> =
        calls.list("search", dao.search(containsPattern(query), limit.coerceAtLeast(0))) { it.toModel() }

    override suspend fun get(id: SessionId): Outcome<HistoryEntry?, StorageError> = calls.runFlat("get") {
        val row = dao.get(id.value) ?: return@runFlat Outcome.Ok(null)
        row.toModel()?.let { Outcome.Ok(it) } ?: Outcome.Err(StorageFailed)
    }

    override suspend fun upsert(entry: HistoryEntry): Outcome<Unit, StorageError> =
        calls.run("upsert") { dao.upsert(entry.toEntity()) }

    override suspend fun delete(id: SessionId): Outcome<Unit, StorageError> = calls.run("delete") {
        dao.delete(id.value)
        dao.pragma(HistoryPragmas.TruncateLog)
    }

    override suspend fun deleteOlderThan(cutoff: Instant): Outcome<Int, StorageError> =
        calls.run("deleteOlderThan") { dao.deleteOlderThan(cutoff.toEpochMillis()) }

    override fun count(): Flow<Int> =
        calls.list("count", dao.count().map { listOf(it) }) { it }.map { it.singleOrNull() ?: 0 }

    override suspend fun deleteAll(): Outcome<Int, StorageError> = calls.run("deleteAll") {
        dao.deleteAll().also { dao.pragma(HistoryPragmas.TruncateLog) }
    }

    override fun list(archived: Boolean, limit: Int): Flow<List<HistoryEntry>> =
        calls.list("list", dao.list(archived, limit.coerceAtLeast(0))) { it.toModel() }

    override fun search(query: String, archived: Boolean, limit: Int): Flow<List<HistoryEntry>> =
        calls.list("searchIn", dao.search(containsPattern(query), archived, limit.coerceAtLeast(0))) { it.toModel() }

    override suspend fun setArchived(id: SessionId, archived: Boolean): Outcome<Unit, StorageError> =
        calls.run("setArchived") { dao.setArchived(id.value, archived) }

    override fun archivedCount(): Flow<Int> =
        calls.list("archivedCount", dao.archivedCount().map { listOf(it) }) { it }.map { it.singleOrNull() ?: 0 }

    override suspend fun deleteAll(includeArchived: Boolean): Outcome<Int, StorageError> = calls.run("deleteSome") {
        val deleted = if (includeArchived) dao.deleteAll() else dao.deleteUnarchived()
        deleted.also { dao.pragma(HistoryPragmas.TruncateLog) }
    }

    private companion object {
        /** A `LIKE` pattern matching [query] anywhere, with `\`, `%` and `_` escaped. */
        fun containsPattern(query: String): String {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            return "%$escaped%"
        }
    }
}
