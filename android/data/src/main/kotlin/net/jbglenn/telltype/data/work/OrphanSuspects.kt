package net.jbglenn.telltype.data.work

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.data.storage.StorageCalls

/**
 * The recordings the previous retention run found orphaned. The retention job deletes an orphan only when it is
 * still orphaned a day later, so it never races with a session that is recording right now or with the start-up
 * crash recovery that queues leftovers (`RecordingStore.findOrphans` is meant for that recovery first).
 */
internal interface OrphanSuspects {
    /** The suspects of the previous run. */
    suspend fun load(): Outcome<Set<RecordingId>, StorageError>

    /** Replaces the suspects with [suspects]. */
    suspend fun save(suspects: Set<RecordingId>): Outcome<Unit, StorageError>
}

/** [OrphanSuspects] in a small Preferences DataStore of its own (`data_maintenance`). */
internal class DataStoreOrphanSuspects(
    private val store: DataStore<Preferences>,
    dispatchers: DispatcherProvider,
    logger: Logger,
) : OrphanSuspects {
    private val calls = StorageCalls(dispatchers, logger, LogTag("OrphanSuspects"))

    override suspend fun load(): Outcome<Set<RecordingId>, StorageError> = calls.run("load") {
        store.data.first()[KEY].orEmpty().map(::RecordingId).toSet()
    }

    override suspend fun save(suspects: Set<RecordingId>): Outcome<Unit, StorageError> = calls.run("save") {
        store.edit { it[KEY] = suspects.map(RecordingId::value).toSet() }
    }.map { }

    private companion object {
        val KEY = stringSetPreferencesKey("orphan_suspects")
    }
}
