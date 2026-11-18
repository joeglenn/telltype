package net.jbglenn.telltype.data.work

import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import javax.inject.Provider
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.data.context
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.domain.coordinator.CrashRecovery
import net.jbglenn.telltype.domain.coordinator.RecoveryLedger
import net.jbglenn.telltype.domain.coordinator.RecoveryStores
import net.jbglenn.telltype.testing.FakeDictionaryRepository
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakeNotifier
import net.jbglenn.telltype.testing.FakePendingJobRepository
import net.jbglenn.telltype.testing.FakeRecordingStore
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FakeSpeechToTextEngine
import net.jbglenn.telltype.testing.FixedClock
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkersTest {
    private val jobs = FakePendingJobRepository()
    private val history = FakeHistoryRepository()
    private val store = FakeRecordingStore()
    private val engine = FakeSpeechToTextEngine()
    private val clock = FixedClock()
    private val suspects = MemorySuspects()
    private val ledger = RecoveryLedger()
    private val notifier = FakeNotifier()

    private class MemorySuspects : OrphanSuspects {
        var saved: Set<RecordingId> = emptySet()
        var failing = false

        override suspend fun load(): Outcome<Set<RecordingId>, StorageError> =
            if (failing) Outcome.Err(StorageFailed) else Outcome.Ok(saved)

        override suspend fun save(suspects: Set<RecordingId>): Outcome<Unit, StorageError> {
            saved = suspects
            return Outcome.Ok(Unit)
        }
    }

    private fun TestScope.factory(): DataWorkerFactory {
        val logger = RecordingLogger()
        val pending = PendingJobProcessor(
            jobs = jobs,
            recordings = store,
            engine = engine,
            text = QueuedText(FakeDictionaryRepository(), { transcript, _ -> transcript }, testDispatchers()),
            outcomes = JobOutcomes(
                jobs,
                JobHistory(history, FakeSettingsRepository(), logger),
                notifier,
                store,
                testDispatchers(),
                logger,
            ),
            logger = logger,
        )
        val retention = RetentionProcessor(
            FakeSettingsRepository(),
            history,
            OrphanSweeper(jobs, store, suspects, ledger, logger),
            clock,
            logger,
        )
        return DataWorkerFactory(Provider { pending }, Provider { retention })
    }

    private inline fun <reified W : ListenableWorker> TestScope.worker(): W =
        TestListenableWorkerBuilder<W>(context()).setWorkerFactory(factory()).build()

    @Test
    fun `the pending-job worker succeeds when the queue is done`() = runTest {
        store.leave(RecordingId("recording-1"), SessionId("session-1"))
        jobs.enqueue(Fixtures.pendingJob())

        assertEquals(ListenableWorker.Result.success(), worker<PendingJobWorker>().doWork())
        assertEquals(emptyList<Any>(), jobs.jobs.first())
    }

    @Test
    fun `the pending-job worker asks for a retry while the server is unreachable`() = runTest {
        store.leave(RecordingId("recording-1"), SessionId("session-1"))
        jobs.enqueue(Fixtures.pendingJob())
        engine.transcribeCall.returns(Outcome.Err(ServerUnreachable))

        assertEquals(ListenableWorker.Result.retry(), worker<PendingJobWorker>().doWork())
    }

    @Test
    fun `retention deletes history older than the setting`() = runTest {
        history.upsert(Fixtures.historyEntry(id = "old", createdAt = Fixtures.Epoch))
        history.upsert(Fixtures.historyEntry(id = "new", createdAt = Fixtures.Epoch + 20.days))
        clock.advanceBy(35.days)

        assertEquals(ListenableWorker.Result.success(), worker<RetentionWorker>().doWork())

        assertEquals(listOf("new"), history.entries.map { it.id.value })
    }

    @Test
    fun `retention deletes an orphaned recording only when it was orphaned at the previous run too`() = runTest {
        store.leave(RecordingId("owned"), SessionId("s1"))
        store.leave(RecordingId("orphan"), SessionId("s2"))
        jobs.enqueue(Fixtures.pendingJob("owned"))
        ledger.markExamined(listOf(RecordingId("orphan"), RecordingId("new-orphan")))

        worker<RetentionWorker>().doWork()
        assertEquals(
            "first sighting: only suspected",
            setOf(RecordingId("owned"), RecordingId("orphan")),
            store.recordings,
        )
        assertEquals(setOf(RecordingId("orphan")), suspects.saved)

        store.leave(RecordingId("new-orphan"), SessionId("s3"))
        worker<RetentionWorker>().doWork()
        assertEquals(setOf(RecordingId("owned"), RecordingId("new-orphan")), store.recordings)
        assertEquals(setOf(RecordingId("new-orphan")), suspects.saved)
    }

    @Test
    fun `retention never deletes a recording crash recovery has not examined`() = runTest {
        store.leave(RecordingId("crashed"), SessionId("s1"))

        repeat(3) { worker<RetentionWorker>().doWork() }

        assertEquals(setOf(RecordingId("crashed")), store.recordings)
        assertEquals(setOf(RecordingId("crashed")), suspects.saved)
    }

    @Test
    fun `service never connects - recovery at app start queues the recording and two sweeps keep it`() = runTest {
        store.leave(RecordingId("crashed"), SessionId("s1"))
        val recovery = CrashRecovery(
            RecoveryStores(store, jobs, FakeSettingsRepository()),
            notifier,
            ledger,
            clock,
            RecordingLogger(),
        )

        recovery.run()
        recovery.run()
        repeat(2) { worker<RetentionWorker>().doWork() }

        assertEquals(listOf(RecordingId("crashed")), jobs.jobs.first().map { it.recording })
        assertEquals(setOf(RecordingId("crashed")), store.recordings)
        assertEquals(listOf<Notice>(Notice.DictationsRecovered(1)), notifier.postCall.calls)
    }

    @Test
    fun `retention retries when storage fails`() = runTest {
        history.faults.failure = StorageFailed

        assertEquals(ListenableWorker.Result.retry(), worker<RetentionWorker>().doWork())
    }
}
