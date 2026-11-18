package net.jbglenn.telltype.data.work

import javax.inject.Inject
import kotlinx.coroutines.flow.first
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.domain.coordinator.RecoveryLedger
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.RecordingStore

/**
 * Deletes recordings that belong to no pending job ([RecordingStore.findOrphans]) and were already orphaned at the
 * previous sweep ([OrphanSuspects]), so a recording that a session is writing right now is never touched, and that
 * crash recovery has examined in this process ([RecoveryLedger]), so a recording left by a crash is never deleted
 * before recovery has tried to queue it (A7h).
 */
internal class OrphanSweeper @Inject constructor(
    private val jobs: PendingJobRepository,
    private val recordings: RecordingStore,
    private val suspects: OrphanSuspects,
    private val ledger: RecoveryLedger,
    private val logger: Logger,
) {
    /** Runs one sweep; false when a step failed and a later run should try again. */
    suspend fun sweep(): Boolean {
        val owned = jobs.jobs.first().map { it.recording }.toSet()
        val orphans = when (val found = recordings.findOrphans(owned)) {
            is Outcome.Ok -> found.value.toSet()
            is Outcome.Err -> return false
        }
        val previous = when (val loaded = suspects.load()) {
            is Outcome.Ok -> loaded.value
            is Outcome.Err -> emptySet()
        }
        val kept = mutableSetOf<RecordingId>()
        for (orphan in orphans) {
            if (orphan !in previous || !ledger.wasExamined(orphan)) {
                kept += orphan
            } else if (recordings.delete(orphan) is Outcome.Err) {
                logger.warn(TAG, null) { "orphaned recording ${orphan.value} not deleted" }
                kept += orphan
            }
        }
        logger.info(TAG) { "deleted ${orphans.size - kept.size} orphaned recordings, ${kept.size} suspected" }
        return suspects.save(kept) is Outcome.Ok
    }

    private companion object {
        val TAG = LogTag("Retention")
    }
}
