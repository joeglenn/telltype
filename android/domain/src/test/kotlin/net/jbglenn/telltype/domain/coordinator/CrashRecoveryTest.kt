package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Start-up recovery: recordings a crash left behind are repaired, queued and announced once. */
class CrashRecoveryTest {
    @Test
    fun `orphaned recordings are queued with one notice and owned ones are left alone`() = runTest {
        val w = CoordinatorWorld(this)
        w.recordings.leave(RecordingId("crashed-1"), SessionId("old-1"))
        w.recordings.leave(RecordingId("crashed-2"), SessionId("old-2"))
        w.recordings.leave(RecordingId("queued"), SessionId("old-3"))
        w.pendingJobs.enqueue(Fixtures.pendingJob(recording = "queued"))
        w.start()
        w.idle()
        val jobs = w.pendingJobs.jobs.first().associateBy { it.recording.value }
        assertEquals(setOf("queued", "crashed-1", "crashed-2"), jobs.keys)
        assertEquals("unexpected", jobs.getValue("crashed-1").lastErrorCode)
        assertEquals(AppCategory.OTHER, jobs.getValue("crashed-1").category)
        assertEquals(SessionId("old-2"), jobs.getValue("crashed-2").session)
        assertEquals(null, jobs.getValue("queued").lastErrorCode)
        assertEquals(listOf<Notice>(Notice.DictationsRecovered(2)), w.notifier.postCall.calls)
        w.closeAndAssertStopped()
    }

    @Test
    fun `nothing to recover posts nothing, and recovery runs before the first session`() = runTest {
        val w = CoordinatorWorld(this)
        w.coordinator.dispatch(SessionEvent.FocusChanged(Focus.Available(w.field, BubbleMode.DICTATE)))
        w.coordinator.dispatch(SessionEvent.MicPressed)
        w.start()
        assertInstanceOf(SessionState.Listening::class.java, w.state)
        assertTrue(w.notifier.postCall.calls.isEmpty())
        assertTrue(w.pendingJobs.jobs.first().isEmpty(), "the live recording was not taken for an orphan")
        w.closeAndAssertStopped()
    }

    @Test
    fun `recovery at app start and again on service connect queues each recording once`() = runTest {
        val w = CoordinatorWorld(this)
        w.recordings.leave(RecordingId("crashed-1"), SessionId("old-1"))
        val atAppStart = CrashRecovery(
            RecoveryStores(w.recordings, w.pendingJobs, w.settingsRepository),
            w.notifier,
            w.ledger,
            w.clock,
            w.logger,
        )

        atAppStart.run()
        atAppStart.run()
        w.start()
        w.idle()

        assertEquals(listOf(RecordingId("crashed-1")), w.pendingJobs.jobs.first().map { it.recording })
        assertEquals(listOf<Notice>(Notice.DictationsRecovered(1)), w.notifier.postCall.calls)
        assertTrue(w.ledger.wasExamined(RecordingId("crashed-1")))
        w.closeAndAssertStopped()
    }

    @Test
    fun `a recording from an incognito field is recovered as an incognito job`() = runTest {
        val w = CoordinatorWorld(this)
        w.recordings.leave(RecordingId("crashed-1"), SessionId("old-1"), private = true)
        w.start()
        w.idle()
        val job = w.pendingJobs.jobs.first().single()
        assertTrue(job.incognito)
        assertEquals(null, job.app)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a dictation in an incognito field records with the mark and queues as incognito`() = runTest {
        val w = CoordinatorWorld(this, field = Fixtures.fieldTarget().copy(incognito = true))
        w.start()
        w.speech.transcribeCall.returns(Outcome.Err(ServerUnreachable))
        w.send(SessionEvent.MicPressed, SessionEvent.FinishTapped)
        w.idle()
        assertEquals(w.recordings.incognitoRecordings, w.pendingJobs.jobs.first().map { it.recording }.toSet())
        assertTrue(w.pendingJobs.jobs.first().single().incognito)
        assertTrue(w.history.entries.isEmpty())
        w.closeAndAssertStopped()
    }

    @Test
    fun `a failing store is logged and dictation still works`() = runTest {
        val w = CoordinatorWorld(this)
        w.recordings.leave(RecordingId("crashed-1"), SessionId("old-1"))
        w.recordings.faults.failure = StorageFailed
        w.start()
        w.idle()
        assertTrue(w.notifier.postCall.calls.isEmpty())
        assertTrue(w.logger.entries.any { "orphan scan failed: storage_failed" in it.message })
        w.recordings.faults.failure = null
        w.dictate("hello world")
        assertInstanceOf(SessionState.Done::class.java, w.state)
        w.closeAndAssertStopped()
    }
}
