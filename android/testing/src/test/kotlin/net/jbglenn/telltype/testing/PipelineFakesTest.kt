package net.jbglenn.telltype.testing

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioLevel
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // currentTime, advanceTimeBy, runCurrent
class PipelineFakesTest {
    private val sessionId = SessionId("session-1")

    @Test
    fun `speech engine transcribes by default, then as scripted, after the delay`() = runTest {
        val engine = FakeSpeechToTextEngine()
        val clip = Fixtures.audioClip()
        engine.transcribeCall.delay = 2.seconds
        engine.transcribeCall.returnsNext(Outcome.Ok(Fixtures.transcript()), Outcome.Err(ServerUnreachable))

        engine.prepare()
        assertEquals(Outcome.Ok(Fixtures.transcript()), engine.transcribe(clip, SttHints(listOf("Oslo"))))
        assertEquals(Outcome.Err(ServerUnreachable), engine.transcribe(clip, SttHints(emptyList())))
        assertEquals(1, engine.prepareCount)
        assertEquals(clip to SttHints(listOf("Oslo")), engine.transcribeCall.calls.first())
        assertEquals(4_000, currentTime)
    }

    @Test
    fun `cleaner cleans by default and keeps a selection unchanged for commands`() = runTest {
        val cleaner = FakeTextCleaner()
        val request = CleanupRequest(
            session = sessionId,
            transcript = "hello world",
            tone = Tone.CASUAL,
            category = AppCategory.PERSONAL_CHAT,
            dictionary = emptyList(),
            contextBefore = "",
            fieldHint = null,
        )

        assertEquals(Outcome.Ok(Fixtures.cleanedText()), cleaner.clean(request))
        assertEquals(
            Outcome.Ok(Fixtures.cleanedText(text = "draft")),
            cleaner.command(CommandRequest(sessionId, selection = "draft", instruction = "shorter")),
        )
        cleaner.cleanCall.returns(Outcome.Err(CleanupTimedOut))
        assertEquals(Outcome.Err(CleanupTimedOut), cleaner.clean(request))
        assertEquals(listOf(request, request), cleaner.cleanCall.calls)
    }

    @Test
    fun `inserter echoes the text into the target and can refuse`() = runTest {
        val inserter = FakeTextInserter()
        val target = Fixtures.fieldTarget()

        val receipt = (inserter.insert(target, "Hi.") as Outcome.Ok).value
        assertEquals(Fixtures.insertReceipt(target = target, text = "Hi."), receipt)
        val raw = (inserter.replace(receipt, "hi") as Outcome.Ok).value
        assertEquals("hi", raw.text)
        assertEquals(0, raw.range?.start)
        inserter.insertCall.returns(Outcome.Err(FieldChanged))
        assertEquals(Outcome.Err(FieldChanged), inserter.insert(target, "Hi."))
    }

    @Test
    fun `audio capture tracks the active recording and forwards levels and interruptions`() = runTest {
        val capture = FakeAudioCapture()
        val recording = RecordingId("recording-7")

        capture.levels.test {
            capture.emitLevel(AudioLevel(0.5f))
            assertEquals(AudioLevel(0.5f), awaitItem())
        }
        capture.interruptions.test {
            capture.interrupt(Interruption.CALL_STARTED)
            assertEquals(Interruption.CALL_STARTED, awaitItem())
        }
        assertEquals(Outcome.Ok(Unit), capture.start(recording))
        assertEquals(recording, capture.active)
        assertEquals(Outcome.Ok(Fixtures.audioClip(recording = "recording-7")), capture.stop())
        assertNull(capture.active)
        capture.startCall.returns(Outcome.Err(MicrophoneInUse))
        assertEquals(Outcome.Err(MicrophoneInUse), capture.start(recording))
        capture.cancel()
        assertEquals(1, capture.cancelCount)
    }

    @Test
    fun `recording store creates, finalises, deletes and finds orphans`() = runTest {
        val store = FakeRecordingStore()

        val first = (store.create(sessionId) as Outcome.Ok).value
        val second = (store.create(sessionId) as Outcome.Ok).value
        assertEquals(Outcome.Ok(Fixtures.audioClip(recording = first.value)), store.finalise(first))
        assertEquals(Outcome.Ok(listOf(second)), store.findOrphans(owned = setOf(first)))
        assertEquals(Outcome.Ok(Unit), store.delete(first))
        assertEquals(Outcome.Err(StorageFailed), store.finalise(first))
        store.faults.failure = StorageFull
        assertEquals(Outcome.Err(StorageFull), store.create(sessionId))
        assertEquals(setOf(second), store.recordings)
    }

    @Test
    fun `focused field source publishes what the test focuses`() = runTest {
        val source = FakeFocusedFieldSource()
        val snapshot = Fixtures.fieldSnapshot()

        source.focused.test {
            assertNull(awaitItem())
            source.focus(snapshot)
            assertEquals(snapshot, awaitItem())
        }
    }

    @Test
    fun `context reader returns empty context unless scripted`() = runTest {
        val reader = FakeFieldContextReader()
        val target = Fixtures.fieldTarget()
        val context = FieldContext(textBefore = "Dear Sam,", textAfter = "", selectedText = "")

        assertEquals(FieldContext.Empty, reader.read(target, 500, 200))
        reader.readCall.returns(context)
        assertEquals(context, reader.read(target, 500, 200))
        assertEquals(Triple(target, 500, 200), reader.readCall.calls.first())
    }

    @Test
    fun `edit watcher forwards edits until its window has passed`() = runTest {
        val watcher = FakeFieldEditWatcher()
        val target = Fixtures.fieldTarget()

        watcher.edits(target, 60.seconds).test {
            runCurrent()
            watcher.type("Hello world.")
            assertEquals("Hello world.", awaitItem())
            advanceTimeBy(61.seconds)
            awaitComplete()
        }
        assertEquals(listOf(target to 60.seconds), watcher.watches)
    }
}
