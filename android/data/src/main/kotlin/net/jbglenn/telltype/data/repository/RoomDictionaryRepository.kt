package net.jbglenn.telltype.data.repository

import androidx.room.withTransaction
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.Replacement
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.data.db.DictionaryDao
import net.jbglenn.telltype.data.db.TelltypeDatabase
import net.jbglenn.telltype.data.mapping.toEntity
import net.jbglenn.telltype.data.mapping.toModel
import net.jbglenn.telltype.data.storage.StorageCalls
import net.jbglenn.telltype.domain.port.DictionaryRepository

/** [DictionaryRepository] on the Room `dictionary_term` and `replacement` tables. */
internal class RoomDictionaryRepository @Inject constructor(
    private val database: TelltypeDatabase,
    private val clock: Clock,
    dispatchers: DispatcherProvider,
    logger: Logger,
) : DictionaryRepository {
    private val dao: DictionaryDao = database.dictionary()
    private val calls = StorageCalls(dispatchers, logger, LogTag("DictionaryRepo"))

    override val terms: Flow<List<DictionaryTerm>> = calls.list("terms", dao.terms()) { it.toModel() }

    override val replacements: Flow<List<Replacement>> =
        calls.list("replacements", dao.replacements()) { it.toModel() }

    override suspend fun addTerm(
        term: String,
        soundsLike: List<String>,
        source: EntrySource,
    ): Outcome<DictionaryTerm, StorageError> = calls.run("addTerm") {
        val draft = DictionaryTerm(DictionaryTermId(0), term, soundsLike, source, uses = 0, createdAt = clock.now())
        draft.copy(id = DictionaryTermId(dao.insertTerm(draft.toEntity())))
    }

    override suspend fun updateTerm(term: DictionaryTerm): Outcome<Unit, StorageError> =
        calls.runFlat("updateTerm") { changed(dao.updateTerm(term.toEntity())) }

    override suspend fun deleteTerm(id: DictionaryTermId): Outcome<Unit, StorageError> = calls.run("deleteTerm") {
        dao.deleteTerm(id.value)
    }.map { }

    override suspend fun addReplacement(
        heard: String,
        replaceWith: String,
        source: EntrySource,
    ): Outcome<Replacement, StorageError> = calls.run("addReplacement") {
        val draft = Replacement(ReplacementId(0), heard, replaceWith, source, uses = 0, createdAt = clock.now())
        draft.copy(id = ReplacementId(dao.insertReplacement(draft.toEntity())))
    }

    override suspend fun updateReplacement(replacement: Replacement): Outcome<Unit, StorageError> =
        calls.runFlat("updateReplacement") { changed(dao.updateReplacement(replacement.toEntity())) }

    override suspend fun deleteReplacement(id: ReplacementId): Outcome<Unit, StorageError> =
        calls.run("deleteReplacement") {
            dao.deleteReplacement(id.value)
        }.map { }

    override suspend fun recordUses(
        terms: Set<DictionaryTermId>,
        replacements: Set<ReplacementId>,
    ): Outcome<Unit, StorageError> = calls.run("recordUses") {
        database.withTransaction {
            if (terms.isNotEmpty()) dao.addTermUses(terms.map { it.value })
            if (replacements.isNotEmpty()) dao.addReplacementUses(replacements.map { it.value })
        }
    }.map { }
}
