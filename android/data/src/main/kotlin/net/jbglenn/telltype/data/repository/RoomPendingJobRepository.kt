package net.jbglenn.telltype.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.data.db.PendingJobDao
import net.jbglenn.telltype.data.mapping.toEntity
import net.jbglenn.telltype.data.mapping.toModel
import net.jbglenn.telltype.data.storage.StorageCalls
import net.jbglenn.telltype.data.work.PendingWorkScheduler
import net.jbglenn.telltype.domain.port.PendingJobRepository

/**
 * [PendingJobRepository] on the Room `pending_job` table. Every successful [enqueue] also schedules the
 * pending-job worker, so a queued recording is transcribed once the network is back without anyone else
 * remembering to start it.
 */
internal class RoomPendingJobRepository @Inject constructor(
    private val dao: PendingJobDao,
    private val scheduler: PendingWorkScheduler,
    dispatchers: DispatcherProvider,
    private val logger: Logger,
) : PendingJobRepository {
    private val calls = StorageCalls(dispatchers, logger, TAG)

    override val jobs: Flow<List<PendingJob>> = calls.list("jobs", dao.jobs()) { it.toModel() }

    override suspend fun enqueue(job: PendingJob): Outcome<Unit, StorageError> {
        val stored = calls.run("enqueue") { dao.upsert(job.toEntity()) }
        if (stored is Outcome.Ok && !scheduler.schedulePendingJobs()) {
            logger.warn(TAG, null) { "job ${job.recording.value} stored, but the worker could not be scheduled now" }
        }
        return stored
    }

    override suspend fun recordFailure(recording: RecordingId, errorCode: String): Outcome<Unit, StorageError> =
        calls.runFlat("recordFailure") { changed(dao.recordFailure(recording.value, errorCode)) }

    override suspend fun remove(recording: RecordingId): Outcome<Unit, StorageError> = calls.run("remove") {
        dao.delete(recording.value)
    }.map { }

    private companion object {
        val TAG = LogTag("PendingJobRepo")
    }
}
