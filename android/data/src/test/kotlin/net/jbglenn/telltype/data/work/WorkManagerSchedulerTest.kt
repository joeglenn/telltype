package net.jbglenn.telltype.data.work

import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.TimeUnit
import net.jbglenn.telltype.data.context
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkManagerSchedulerTest {
    private val logger = RecordingLogger()

    private fun infos(name: String): List<WorkInfo> =
        WorkManager.getInstance(context()).getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)

    @Test
    fun `pending jobs wait for a network and repeated scheduling appends instead of duplicating work`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context())
        val scheduler = WorkManagerScheduler(context(), logger)

        assertTrue(scheduler.schedulePendingJobs())
        assertTrue(scheduler.schedulePendingJobs())

        val work = infos(WorkManagerScheduler.PENDING_JOBS_WORK)
        assertEquals(2, work.size)
        assertTrue(work.all { it.constraints.requiredNetworkType == NetworkType.CONNECTED })
        assertEquals("the second run waits for the first", 1, work.count { it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun `retention is one daily periodic work, kept when scheduled again`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context())
        val scheduler = WorkManagerScheduler(context(), logger)

        assertTrue(scheduler.scheduleRetention())
        assertTrue(scheduler.scheduleRetention())

        val work = infos(WorkManagerScheduler.RETENTION_WORK).single()
        assertEquals(TimeUnit.DAYS.toMillis(1), work.periodicityInfo!!.repeatIntervalMillis)
        assertTrue(work.constraints.requiresBatteryNotLow())
    }
}
