package net.jbglenn.telltype.data.work

import javax.inject.Inject
import kotlin.time.Duration
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * The daily retention job (docs/PLAN.md sections 11 and Appendix E):
 *
 * - deletes `history` rows older than `Settings.privacy.historyRetention`; [Duration.ZERO] means history is off, so
 *   every row goes;
 * - deletes orphaned recordings ([OrphanSweeper]).
 *
 * Returns false when a step failed, so WorkManager retries later; the other step still runs.
 */
internal class RetentionProcessor @Inject constructor(
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val orphans: OrphanSweeper,
    private val clock: Clock,
    private val logger: Logger,
) {
    /** Runs both steps. */
    suspend fun run(): Boolean {
        val historyDone = pruneHistory()
        val orphansDone = orphans.sweep()
        return historyDone && orphansDone
    }

    private suspend fun pruneHistory(): Boolean {
        val retention = settings.current().privacy.historyRetention
        if (retention.isInfinite()) return true
        val cutoff = clock.now() - retention
        return when (val deleted = history.deleteOlderThan(cutoff)) {
            is Outcome.Ok -> {
                logger.info(TAG) { "deleted ${deleted.value} history entries older than $retention" }
                true
            }

            is Outcome.Err -> false
        }
    }

    private companion object {
        val TAG = LogTag("Retention")
    }
}
