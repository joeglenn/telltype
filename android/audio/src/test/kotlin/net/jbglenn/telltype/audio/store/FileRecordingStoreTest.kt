package net.jbglenn.telltype.audio.store

import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.audio.ParsedWav
import net.jbglenn.telltype.audio.Signal
import net.jbglenn.telltype.audio.Signals
import net.jbglenn.telltype.audio.SplitTestDispatchers
import net.jbglenn.telltype.audio.orFail
import net.jbglenn.telltype.audio.wav.WavFileWriter
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.SequentialIdGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRecordingStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val session = SessionId("session-1")
    private val directory: File get() = File(temp.root, "recordings")
    private val files: RecordingFiles by lazy { RecordingFiles(directory) }

    private fun TestScope.store(
        space: StorageSpace = StorageSpace { Long.MAX_VALUE },
        ids: IdGenerator = SequentialIdGenerator(),
    ) = FileRecordingStore(files, ids, space, SplitTestDispatchers(testScheduler), RecordingLogger())

    /** Writes [seconds] of [signal] into [recording]'s file and leaves it as a crash would: no header update. */
    private fun crashWhileRecording(recording: RecordingId, seconds: Int, signal: Signal) {
        val file = checkNotNull(files.find(recording)).file
        val writer = WavFileWriter.openWriter(file, AudioFormat.Dictation)
        val samples = Signals.samples(signal, seconds * 16_000)
        writer.write(samples, samples.size)
        assertNull(writer.close())
    }

    @Test
    fun createMakesAValidEmptyWavInThePrivateDirectory() = runTest {
        val recording = store().create(session).orFail()

        val file = File(directory, "${recording.value}.${session.value}.wav")
        assertTrue(file.isFile)
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 0)
    }

    @Test
    fun anIncognitoRecordingKeepsItsMarkThroughACrashAndIsFoundDeletedLikeAnyOther() = runTest {
        val store = store()
        val plain = store.create(session).orFail()
        val private = store.create(SessionId("session-2"), incognito = true).orFail()
        crashWhileRecording(private, seconds = 1, signal = Signals.tone(-25.0))

        assertTrue(File(directory, "${private.value}.session-2.incognito.wav").isFile)
        assertEquals(setOf(plain, private), store.findOrphans(emptySet()).orFail().toSet())
        val clip = store.finalise(private).orFail()
        assertTrue("the mark survives the crash", clip.incognito)
        assertEquals(SessionId("session-2"), clip.session)
        assertFalse(store.finalise(plain).orFail().incognito)

        store.delete(private).orFail()
        assertEquals(listOf(plain), store.findOrphans(emptySet()).orFail())
    }

    @Test
    fun finaliseRepairsARecordingLeftByACrash() = runTest {
        val store = store()
        val recording = store.create(session).orFail()
        crashWhileRecording(recording, seconds = 3, signal = Signals.tone(-25.0))
        val file = checkNotNull(files.find(recording)).file
        file.appendBytes(byteArrayOf(0x01))

        val clip = store.finalise(recording).orFail()

        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 96_000)
        assertEquals(3.seconds, clip.duration)
        assertEquals(SpeechPresence.CLEAR, clip.speech)
        assertEquals(session, clip.session)
        assertEquals(recording, clip.recording)
        assertEquals(file.absolutePath, clip.path)
        assertEquals(AudioFormat.Dictation, clip.format)
    }

    @Test
    fun aCrashBeforeAnyAudioGivesAnEmptyFaintClip() = runTest {
        val store = store()
        val recording = store.create(session).orFail()
        val file = checkNotNull(files.find(recording)).file
        file.writeBytes(byteArrayOf(0x52, 0x49, 0x46))

        val clip = store.finalise(recording).orFail()

        assertEquals(Duration.ZERO, clip.duration)
        assertEquals(SpeechPresence.FAINT, clip.speech)
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 0)
    }

    @Test
    fun aSilentRecoveredRecordingIsFaintSoItsTextIsDropped() = runTest {
        val store = store()
        val recording = store.create(session).orFail()
        crashWhileRecording(recording, seconds = 2, signal = Signals.noise(-80.0))

        assertEquals(SpeechPresence.FAINT, store.finalise(recording).orFail().speech)
    }

    @Test
    fun finaliseIsIdempotent() = runTest {
        val store = store()
        val recording = store.create(session).orFail()
        crashWhileRecording(recording, seconds = 1, signal = Signals.tone(-30.0))
        val first = store.finalise(recording).orFail()
        val file = File(first.path)
        file.setLastModified(1_000_000_000_000)
        val bytes = file.readBytes()

        val second = store.finalise(recording).orFail()

        assertEquals(first, second)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1_000_000_000_000, file.lastModified())
    }

    @Test
    fun finaliseOfAnUnknownOrUnsafeRecordingFails() = runTest {
        val store = store()

        assertEquals(Outcome.Err(StorageFailed), store.finalise(RecordingId("nope")))
        assertEquals(Outcome.Err(StorageFailed), store.finalise(RecordingId("../../etc/passwd")))
    }

    @Test
    fun finaliseRefusesTheRecordingBeingWritten() = runTest {
        val store = store()
        val recording = store.create(session).orFail()
        files.markWriting(recording, active = true)

        assertEquals(Outcome.Err(StorageFailed), store.finalise(recording))
    }

    @Test
    fun deleteRemovesTheFileAndAMissingRecordingIsFine() = runTest {
        val store = store()
        val recording = store.create(session).orFail()

        assertEquals(Outcome.Ok(Unit), store.delete(recording))
        assertEquals(emptyList<File>(), checkNotNull(directory.listFiles()).toList())
        assertEquals(Outcome.Ok(Unit), store.delete(recording))
        assertEquals(Outcome.Ok(Unit), store.delete(RecordingId("../outside")))
    }

    @Test
    fun orphansAreTheRecordingsNobodyOwns() = runTest {
        val store = store()
        val owned = store.create(session).orFail()
        val orphan = store.create(SessionId("session-2")).orFail()
        val writing = store.create(SessionId("session-3")).orFail()
        files.markWriting(writing, active = true)
        File(directory, "notes.txt").writeText("not a recording")
        File(directory, "bad name.wav").writeText("not ours either")

        assertEquals(listOf(orphan), store.findOrphans(setOf(owned)).orFail())
    }

    @Test
    fun noOrphansWhenTheDirectoryDoesNotExistYet() = runTest {
        assertEquals(emptyList<RecordingId>(), store().findOrphans(emptySet()).orFail())
    }

    @Test
    fun lowSpaceDeletesTheOldestRecordingsFirst() = runTest {
        val perFile = 5L * 1024 * 1024
        val total = 30L * 1024 * 1024
        val space = StorageSpace { dir -> total - perFile * (dir.listFiles()?.size ?: 0) }
        val store = store(space)
        val oldest = store.create(session).orFail()
        val middle = store.create(session).orFail()
        val newest = store.create(session).orFail()
        checkNotNull(files.find(oldest)).file.setLastModified(1_000_000_000_000)
        checkNotNull(files.find(middle)).file.setLastModified(1_000_000_100_000)
        checkNotNull(files.find(newest)).file.setLastModified(1_000_000_200_000)

        val created = store.create(session).orFail()

        assertNull(files.find(oldest))
        assertTrue(files.find(middle) != null && files.find(newest) != null && files.find(created) != null)
    }

    @Test
    fun theRecordingBeingWrittenIsNeverEvicted() = runTest {
        val space = StorageSpace { dir -> if (dir.listFiles().isNullOrEmpty()) Long.MAX_VALUE else 0L }
        val store = store(space)
        val writing = store.create(session).orFail()
        files.markWriting(writing, active = true)

        assertEquals(Outcome.Err(StorageFull), store.create(session))
        assertTrue(files.find(writing) != null)
    }

    @Test
    fun storageFullWhenEvenEvictionLeavesTooLittle() = runTest {
        val store = store(StorageSpace { FileRecordingStore.MIN_FREE_BYTES - 1 })

        assertEquals(Outcome.Err(StorageFull), store.create(session))
        assertFalse(checkNotNull(directory.listFiles()).any { it.name.endsWith(".wav") })
    }

    @Test
    fun unknownFreeSpaceNeverDeletesRetainedAudio() = runTest {
        val store = store(StorageSpace { null })
        val retained = store.create(session).orFail()

        store.create(session).orFail()

        assertTrue(files.find(retained) != null)
    }

    @Test
    fun aLittleSpaceLeftStillAllowsARecording() = runTest {
        val store = store(StorageSpace { FileRecordingStore.MIN_FREE_BYTES })

        store.create(session).orFail()
    }

    @Test
    fun unsafeIdsNeverBecomeFiles() = runTest {
        val ids = object : IdGenerator {
            override fun newSessionId() = SessionId("s")

            override fun newRecordingId() = RecordingId("../escape")
        }

        assertEquals(Outcome.Err(StorageFailed), store(ids = ids).create(session))
        assertEquals(Outcome.Err(StorageFailed), store().create(SessionId("a/b")))
        assertFalse(File(temp.root, "escape.session-1.wav").exists())
    }

    @Test
    fun aFileThatCannotBeCreatedIsStorageFailed() = runTest {
        temp.root.resolve("recordings").writeText("a file where the directory should be")

        assertEquals(Outcome.Err(StorageFailed), store().create(session))
    }

    @Test
    fun noSpaceIsRecognisedInMessagesAndCauses() {
        assertTrue(IOException("write failed: ENOSPC (No space left on device)").isNoSpace())
        assertTrue(IOException("No space left on device").isNoSpace())
        assertTrue(IOException("wrapper", IOException("ENOSPC")).isNoSpace())
        assertFalse(IOException("EIO (I/O error)").isNoSpace())
        assertFalse(IOException().isNoSpace())
    }

    @Test
    fun aCreateWhoseCallerWasCancelledLeavesNoFile() = runTest {
        lateinit var caller: Job
        val sequential = SequentialIdGenerator()
        // The caller is cancelled while the file is being made: a ✕ or a drag while the bubble was arming.
        val cancelling = object : IdGenerator by sequential {
            override fun newRecordingId(): RecordingId = sequential.newRecordingId().also { caller.cancel() }
        }
        val store = store(ids = cancelling)
        caller = launch { store.create(session, incognito = false) }
        caller.join()

        assertTrue("the caller was cancelled", caller.isCancelled)
        assertEquals("no header-only recording left", emptyList<String>(), directory.list().orEmpty().toList())
    }
}
