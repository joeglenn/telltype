package net.jbglenn.telltype.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn

/**
 * [PendingWorkScheduler] on WorkManager.
 *
 * - Pending jobs: one unique chain [PENDING_JOBS_WORK], network connected, exponential back-off from one minute.
 *   A new request is appended to a running one (`APPEND_OR_REPLACE`), so a job queued while the worker runs is
 *   never missed.
 * - Retention: one unique periodic request [RETENTION_WORK] a day, kept when it already exists.
 */
internal class WorkManagerScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val logger: Logger,
) : PendingWorkScheduler {

    override fun schedulePendingJobs(): Boolean = withWorkManager("pending jobs") { workManager ->
        val request = OneTimeWorkRequestBuilder<PendingJobWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(PENDING_JOBS_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun scheduleRetention(): Boolean = withWorkManager("retention") { workManager ->
        val request = PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(RETENTION_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private inline fun withWorkManager(what: String, schedule: (WorkManager) -> Unit): Boolean = try {
        schedule(WorkManager.getInstance(context))
        true
    } catch (failure: IllegalStateException) {
        logger.warn(TAG, failure) { "could not schedule $what: WorkManager is not initialised" }
        false
    }

    internal companion object {
        /** The unique name of the pending-job chain. */
        const val PENDING_JOBS_WORK = "telltype-pending-jobs"

        /** The unique name of the daily retention work. */
        const val RETENTION_WORK = "telltype-retention"

        private const val BACKOFF_MINUTES = 1L
        private val TAG = LogTag("WorkScheduler")
    }
}
