package net.jbglenn.telltype.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import javax.inject.Inject
import javax.inject.Provider

/** Runs the pending-job queue ([PendingJobProcessor]); a run that left retryable failures asks for a retry. */
internal class PendingJobWorker(
    context: Context,
    params: WorkerParameters,
    private val processor: PendingJobProcessor,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = when (processor.processAll()) {
        QueueRun.DONE -> Result.success()
        QueueRun.RETRY_LATER -> Result.retry()
    }
}

/** Runs the daily retention job ([RetentionProcessor]). */
internal class RetentionWorker(context: Context, params: WorkerParameters, private val processor: RetentionProcessor) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (processor.run()) Result.success() else Result.retry()
}

/**
 * Creates `:data`'s workers with their dependencies. The app's WorkManager configuration must include it (for
 * example in a `DelegatingWorkerFactory`); it returns null for any other worker class, so factories can be
 * combined.
 */
public class DataWorkerFactory @Inject internal constructor(
    private val pendingJobs: Provider<PendingJobProcessor>,
    private val retention: Provider<RetentionProcessor>,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        PendingJobWorker::class.java.name -> PendingJobWorker(appContext, workerParameters, pendingJobs.get())
        RetentionWorker::class.java.name -> RetentionWorker(appContext, workerParameters, retention.get())
        else -> null
    }
}
