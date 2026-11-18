package net.jbglenn.telltype.ui.screens.history

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.MisrecognitionReporter

/**
 * Deletes History entries together with what else holds their text: the "That was wrong" reports made from them.
 * Recordings waiting for the server are not History and stay. Used by History and by Privacy settings.
 */
class HistoryEraser @Inject constructor(
    private val history: HistoryRepository,
    private val reports: MisrecognitionReporter,
) {
    /** How many entries there are, History and Archive together; follows every change. */
    val count: Flow<Int> get() = history.count()

    /** How many entries are in the Archive; follows every change. */
    val archivedCount: Flow<Int> get() = history.archivedCount()

    /** Deletes one entry and its reports. */
    suspend fun delete(id: SessionId): Outcome<Unit, StorageError> = when (val deleted = history.delete(id)) {
        is Outcome.Ok -> reports.forget(id)
        is Outcome.Err -> deleted
    }

    /**
     * Deletes every entry of History and, when [includeArchived], of the Archive, with their reports; returns how
     * many entries went. Reports of archived entries that stay are kept.
     */
    suspend fun deleteAll(includeArchived: Boolean): Outcome<Int, StorageError> =
        if (includeArchived || history.archivedCount().first() == 0) deleteEverything() else deleteHistoryOnly()

    private suspend fun deleteEverything(): Outcome<Int, StorageError> = when (val deleted = history.deleteAll()) {
        is Outcome.Ok -> when (val forgotten = reports.forgetAll()) {
            is Outcome.Ok -> deleted
            is Outcome.Err -> forgotten
        }

        is Outcome.Err -> deleted
    }

    private suspend fun deleteHistoryOnly(): Outcome<Int, StorageError> {
        val ids = history.list(archived = false, limit = Int.MAX_VALUE).first().map { it.id }
        var result: Outcome<Int, StorageError> = history.deleteAll(includeArchived = false)
        if (result is Outcome.Ok) {
            for (id in ids) {
                val forgotten = reports.forget(id)
                if (forgotten is Outcome.Err) {
                    result = forgotten
                    break
                }
            }
        }
        return result
    }
}
