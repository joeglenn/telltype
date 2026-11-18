package net.jbglenn.telltype.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.data.db.SnippetDao
import net.jbglenn.telltype.data.mapping.toEntity
import net.jbglenn.telltype.data.mapping.toModel
import net.jbglenn.telltype.data.storage.StorageCalls
import net.jbglenn.telltype.domain.port.SnippetRepository

/** [SnippetRepository] on the Room `snippet` table. */
internal class RoomSnippetRepository @Inject constructor(
    private val dao: SnippetDao,
    private val clock: Clock,
    dispatchers: DispatcherProvider,
    logger: Logger,
) : SnippetRepository {
    private val calls = StorageCalls(dispatchers, logger, LogTag("SnippetRepo"))

    override val snippets: Flow<List<Snippet>> = calls.list("snippets", dao.snippets()) { it.toModel() }

    override suspend fun add(trigger: String, text: String): Outcome<Snippet, StorageError> = calls.run("add") {
        val draft = Snippet(SnippetId(0), trigger, text, uses = 0, createdAt = clock.now())
        draft.copy(id = SnippetId(dao.insert(draft.toEntity())))
    }

    override suspend fun update(snippet: Snippet): Outcome<Unit, StorageError> =
        calls.runFlat("update") { changed(dao.update(snippet.toEntity())) }

    override suspend fun delete(id: SnippetId): Outcome<Unit, StorageError> = calls.run("delete") {
        dao.delete(id.value)
    }.map { }

    override suspend fun recordUse(id: SnippetId): Outcome<Unit, StorageError> = calls.run("recordUse") {
        dao.addUse(id.value)
    }.map { }
}
