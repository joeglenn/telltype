package net.jbglenn.telltype.data.work

/**
 * Schedules `:data`'s background work on WorkManager (docs/PLAN.md sections 8.5 and 11). Both calls are idempotent
 * and return false, after logging, when WorkManager cannot be reached; nothing throws.
 */
public interface PendingWorkScheduler {
    /**
     * Makes sure the pending-job worker runs once the network is connected, with exponential back-off between
     * failed runs. Called after every enqueue and at app start.
     */
    public fun schedulePendingJobs(): Boolean

    /** Makes sure the daily retention worker is scheduled (history retention and orphaned recordings). */
    public fun scheduleRetention(): Boolean
}
