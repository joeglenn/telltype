package net.jbglenn.telltype.data.work

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.domain.port.RecordingStore
import net.jbglenn.telltype.testing.FakeDictionaryRepository
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakeNotifier
import net.jbglenn.telltype.testing.FakePendingJobRepository
import net.jbglenn.telltype.testing.FakeRecordingStore
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FakeSpeechToTextEngine
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PendingJobProcessorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val jobs = FakePendingJobRepository()
    private val history = FakeHistoryRepository()
    private val dictionary = FakeDictionaryRepository()
    private val store = FakeRecordingStore()
    private val engine = FakeSpeechToTextEngine()
    private val notifier = FakeNotifier()
    private val settings = FakeSettingsRepository()
    private val logger = RecordingLogger()
    private val heard = "lets meet at noon igor"

    /** Rule-based cleanup stand-in (A2b's function): upper-cases the first letter and adds a full stop. */
    private val cleanup = QueuedTextCleanup { transcript, replacements ->
        val replaced = replacements.fold(transcript) { text, r -> text.replace(r.heard, r.replaceWith) }
        replaced.replaceFirstChar(Char::uppercase) + "."
    }

    /** A recording store whose clips have the given speech presence. */
    private class PresenceStore(private val inner: FakeRecordingStore, private val presence: SpeechPresence) :
        RecordingStore by inner {
        override suspend fun finalise(recording: RecordingId): Outcome<AudioClip, StorageError> =
            inner.finalise(recording).map { it.copy(speech = presence) }
    }

    private fun TestScope.processor(recordings: RecordingStore = store) = PendingJobProcessor(
        jobs = jobs,
        recordings = recordings,
        engine = engine,
        text = QueuedText(dictionary, cleanup, testDispatchers()),
        outcomes = JobOutcomes(
            jobs,
            JobHistory(history, settings, logger),
            notifier,
            recordings,
            testDispatchers(),
            logger,
        ),
        logger = logger,
    )

    private suspend fun queue(recording: String = "recording-1", attempts: Int = 0) {
        store.leave(RecordingId(recording), SessionId("session-1"))
        jobs.enqueue(Fixtures.pendingJob(recording).copy(attempts = attempts))
        history.upsert(
            Fixtures.historyEntry(
                id = "session-1",
                finalText = "",
                rawTranscript = "",
                errorCode = "server_unreachable",
            )
                .copy(cleanupStatus = null, insertedVia = InsertedVia.NONE),
        )
    }

    /** Queues [recording] like crash recovery or a queue whose session died before saving: no History entry. */
    private suspend fun queueWithoutHistory(recording: String = "recording-1", incognito: Boolean = false) {
        store.leave(RecordingId(recording), SessionId("session-1"), private = incognito)
        jobs.enqueue(Fixtures.pendingJob(recording).copy(incognito = incognito, lastErrorCode = "unexpected"))
    }

    @Test
    fun `a job whose session never saved History gets an entry from the worker`() = runTest {
        queueWithoutHistory()
        engine.transcribeCall.returns(Outcome.Ok(Transcript(heard, 2.seconds, emptyList())))

        assertEquals(QueueRun.DONE, processor().processAll())

        val entry = history.entries.single()
        val job = Fixtures.pendingJob()
        assertEquals(SessionId("session-1"), entry.id)
        assertEquals("Lets meet at noon igor.", entry.finalText)
        assertEquals(heard, entry.rawTranscript)
        assertEquals(job.app, entry.app)
        assertEquals(job.category, entry.category)
        assertEquals(job.createdAt, entry.createdAt)
        assertEquals(DictationMode.DICTATE, entry.mode)
        assertEquals(CleanupStatus.OFF, entry.cleanupStatus)
        assertEquals(InsertedVia.NONE, entry.insertedVia)
        assertEquals(null, entry.errorCode)
        assertEquals(1, notifier.postCall.calls.size)
    }

    @Test
    fun `a recovered recording of unknown app gets an entry too, and a job without words notes its error`() = runTest {
        queueWithoutHistory()
        jobs.enqueue(Fixtures.pendingJob().copy(app = null, lastErrorCode = "unexpected"))
        engine.transcribeCall.returns(Outcome.Err(EmptyTranscript))

        assertEquals(QueueRun.DONE, processor().processAll())

        val entry = history.entries.single()
        assertEquals(AppPackage(""), entry.app)
        assertEquals("", entry.finalText)
        assertEquals(EmptyTranscript.code, entry.errorCode)
    }

    @Test
    fun `an incognito job never gets a History entry, its text is offered in the notice only`() = runTest {
        queueWithoutHistory(incognito = true)
        engine.transcribeCall.returns(Outcome.Ok(Transcript(heard, 2.seconds, emptyList())))

        assertEquals(QueueRun.DONE, processor().processAll())

        assertEquals(emptyList<Any>(), history.entries)
        val notice = notifier.postCall.calls.single() as Notice.TranscribedLater
        assertEquals("Lets meet at noon igor.", notice.text)
        assertEquals(emptyList<Any>(), jobs.jobs.first())
        assertEquals(emptySet<RecordingId>(), store.recordings)
    }

    @Test
    fun `with History switched off no entry is written`() = runTest {
        settings.update { it.copy(privacy = it.privacy.copy(historyRetention = Duration.ZERO)) }
        queueWithoutHistory()
        engine.transcribeCall.returns(Outcome.Ok(Transcript(heard, 2.seconds, emptyList())))

        assertEquals(QueueRun.DONE, processor().processAll())

        assertEquals(emptyList<Any>(), history.entries)
        assertTrue(notifier.postCall.calls.single() is Notice.TranscribedLater)
    }

    @Test
    fun `with the Save history switch off no entry is written`() = runTest {
        settings.update { it.copy(privacy = it.privacy.copy(saveHistory = false)) }
        queueWithoutHistory()
        engine.transcribeCall.returns(Outcome.Ok(Transcript(heard, 2.seconds, emptyList())))

        assertEquals(QueueRun.DONE, processor().processAll())

        assertEquals(emptyList<Any>(), history.entries)
        assertTrue(notifier.postCall.calls.single() is Notice.TranscribedLater)
    }

    @Test
    fun `a parked incognito job writes no History either`() = runTest {
        queueWithoutHistory(incognito = true)
        jobs.enqueue(
            Fixtures.pendingJob().copy(incognito = true, attempts = PendingJobProcessor.MAX_JOB_ATTEMPTS - 1),
        )
        engine.transcribeCall.returns(Outcome.Err(ServerUnreachable))

        assertEquals(QueueRun.DONE, processor().processAll())

        assertEquals(emptyList<Any>(), history.entries)
        assertEquals(listOf<Notice>(Notice.ProblemWhileHidden(ServerUnreachable)), notifier.postCall.calls)
    }

    @Test
    fun `a queued recording is transcribed, saved to History, offered in a notice and its audio deleted`() = runTest {
        queue()
        dictionary.addTerm("Igor", emptyList(), EntrySource.MANUAL)
        dictionary.addReplacement("igor", "Igor", EntrySource.LEARNED)
        val timings = listOf(ServerTiming("stt", 120.milliseconds))
        engine.transcribeCall.returns(Outcome.Ok(Transcript(heard, 2.seconds, timings)))

        assertEquals(QueueRun.DONE, processor().processAll())

        val expected = "Lets meet at noon Igor."
        val (clip, hints) = engine.transcribeCall.calls.single()
        assertEquals(RecordingId("recording-1"), clip.recording)
        assertEquals(SttHints(listOf("Igor")), hints)
        val entry = history.entries.single()
        assertEquals(expected, entry.finalText)
        assertEquals(heard, entry.rawTranscript)
        assertEquals(CleanupStatus.OFF, entry.cleanupStatus)
        assertEquals(InsertedVia.NONE, entry.insertedVia)
        assertEquals(null, entry.errorCode)
        assertEquals(timings, entry.timings.server)
        assertEquals(listOf<Notice>(Notice.TranscribedLater(SessionId("session-1"), expected)), notifier.postCall.calls)
        assertEquals(emptyList<Any>(), jobs.jobs.first())
        assertEquals(emptySet<RecordingId>(), store.recordings)
        assertFalse("no dictated text in the log", logger.entries.any { heard in it.message || expected in it.message })
    }

    @Test
    fun `an unreachable server counts an attempt and asks for a retry, keeping job and audio`() = runTest {
        queue()
        engine.transcribeCall.returns(Outcome.Err(ServerUnreachable))

        assertEquals(QueueRun.RETRY_LATER, processor().processAll())

        val job = jobs.jobs.first().single()
        assertEquals(1, job.attempts)
        assertEquals("server_unreachable", job.lastErrorCode)
        assertEquals(setOf(RecordingId("recording-1")), store.recordings)
        assertEquals(emptyList<Notice>(), notifier.postCall.calls)
    }

    @Test
    fun `the last allowed attempt parks the job and tells the user once`() = runTest {
        queue(attempts = PendingJobProcessor.MAX_JOB_ATTEMPTS - 1)
        engine.transcribeCall.returns(Outcome.Err(KeyRejected))

        assertEquals(QueueRun.DONE, processor().processAll())
        assertEquals(listOf<Notice>(Notice.ProblemWhileHidden(KeyRejected)), notifier.postCall.calls)
        assertEquals("key_rejected", history.entries.single().errorCode)
        assertEquals(setOf(RecordingId("recording-1")), store.recordings)

        assertEquals("a parked job is not tried again", QueueRun.DONE, processor().processAll())
        assertEquals(1, engine.transcribeCall.calls.size)
    }

    @Test
    fun `an empty transcript ends the job without text`() = runTest {
        queue()
        engine.transcribeCall.returns(Outcome.Err(EmptyTranscript))

        assertEquals(QueueRun.DONE, processor().processAll())

        assertEquals("empty_transcript", history.entries.single().errorCode)
        assertEquals(listOf<Notice>(Notice.ProblemWhileHidden(EmptyTranscript)), notifier.postCall.calls)
        assertEquals(emptyList<Any>(), jobs.jobs.first())
        assertEquals(emptySet<RecordingId>(), store.recordings)
    }

    @Test
    fun `a faint recording is never sent and ends as NoSpeech`() = runTest {
        queue()

        assertEquals(QueueRun.DONE, processor(PresenceStore(store, SpeechPresence.FAINT)).processAll())

        assertEquals(emptyList<Any>(), engine.transcribeCall.calls)
        assertEquals(listOf<Notice>(Notice.ProblemWhileHidden(NoSpeech)), notifier.postCall.calls)
        assertEquals(emptySet<RecordingId>(), store.recordings)
    }

    @Test
    fun `a recording that is gone ends the job, one that cannot be read now is retried`() = runTest {
        jobs.enqueue(Fixtures.pendingJob("recording-gone"))
        assertEquals(QueueRun.DONE, processor().processAll())
        assertEquals(listOf<Notice>(Notice.ProblemWhileHidden(StorageFailed)), notifier.postCall.calls)
        assertEquals(emptyList<Any>(), jobs.jobs.first())

        val onDisk = File(folder.root, "recording-busy.wav").apply { writeBytes(ByteArray(4)) }
        jobs.enqueue(Fixtures.pendingJob("recording-busy").copy(audioPath = onDisk.absolutePath))
        assertEquals(QueueRun.RETRY_LATER, processor().processAll())
        assertEquals("storage_failed", jobs.jobs.first().single().lastErrorCode)
    }

    @Test
    fun `several jobs are processed in one run and a failure of one does not stop the others`() = runTest {
        queue("recording-1")
        queue("recording-2")
        engine.transcribeCall.returnsNext(Outcome.Err(ServerUnreachable), Outcome.Ok(Fixtures.transcript()))

        assertEquals(QueueRun.RETRY_LATER, processor().processAll())

        assertEquals(listOf("recording-1"), jobs.jobs.first().map { it.recording.value })
        assertEquals(1, notifier.postCall.calls.size)
    }

    @Test
    fun `an empty queue is done at once`() = runTest {
        assertEquals(QueueRun.DONE, processor().processAll())
        assertEquals(emptyList<Any>(), engine.transcribeCall.calls)
    }
}
