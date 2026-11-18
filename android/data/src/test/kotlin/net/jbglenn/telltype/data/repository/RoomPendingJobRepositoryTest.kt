package net.jbglenn.telltype.data.repository

import app.cash.turbine.test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.data.FakeScheduler
import net.jbglenn.telltype.data.inMemoryDatabase
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomPendingJobRepositoryTest {
    private val scheduler = FakeScheduler()
    private val logger = RecordingLogger()

    @Test
    fun `enqueue stores the job, replaces one for the same recording and schedules the worker`() = runTest {
        val repository =
            RoomPendingJobRepository(inMemoryDatabase().pendingJobs(), scheduler, testDispatchers(), logger)
        val job = Fixtures.pendingJob("recording-1")

        assertEquals(Outcome.Ok(Unit), repository.enqueue(job))
        assertEquals(Outcome.Ok(Unit), repository.enqueue(job.copy(audioPath = "/data/recordings/moved.wav")))

        assertEquals(listOf(job.copy(audioPath = "/data/recordings/moved.wav")), repository.jobs.first())
        assertEquals(2, scheduler.pendingJobsScheduled)
    }

    @Test
    fun `jobs are oldest first and the flow follows changes`() = runTest {
        val repository =
            RoomPendingJobRepository(inMemoryDatabase().pendingJobs(), scheduler, testDispatchers(), logger)
        repository.enqueue(Fixtures.pendingJob("recording-2", createdAt = Fixtures.Epoch + 5.minutes))

        repository.jobs.test {
            assertEquals(listOf("recording-2"), awaitItem().map { it.recording.value })
            repository.enqueue(Fixtures.pendingJob("recording-1"))
            assertEquals(listOf("recording-1", "recording-2"), awaitItem().map { it.recording.value })
            repository.remove(RecordingId("recording-2"))
            assertEquals(listOf("recording-1"), awaitItem().map { it.recording.value })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recordFailure counts attempts and keeps the last error, a missing job fails`() = runTest {
        val repository =
            RoomPendingJobRepository(inMemoryDatabase().pendingJobs(), scheduler, testDispatchers(), logger)
        repository.enqueue(Fixtures.pendingJob("recording-1"))

        repository.recordFailure(RecordingId("recording-1"), "server_unreachable")
        assertEquals(Outcome.Ok(Unit), repository.recordFailure(RecordingId("recording-1"), "key_rejected"))

        val job = repository.jobs.first().single()
        assertEquals(2, job.attempts)
        assertEquals("key_rejected", job.lastErrorCode)
        assertEquals(Outcome.Err(StorageFailed), repository.recordFailure(RecordingId("missing"), "x"))
        assertEquals(Outcome.Ok(Unit), repository.remove(RecordingId("missing")))
    }

    @Test
    fun `a job is stored even when the worker cannot be scheduled now`() = runTest {
        scheduler.available = false
        val repository =
            RoomPendingJobRepository(inMemoryDatabase().pendingJobs(), scheduler, testDispatchers(), logger)

        assertEquals(Outcome.Ok(Unit), repository.enqueue(Fixtures.pendingJob()))
        assertEquals(1, repository.jobs.first().size)
        assertTrue(logger.entries.any { "could not be scheduled" in it.message })
    }
}
