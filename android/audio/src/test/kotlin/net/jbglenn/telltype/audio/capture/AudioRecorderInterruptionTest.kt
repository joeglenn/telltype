package net.jbglenn.telltype.audio.capture

import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.audio.ParsedWav
import net.jbglenn.telltype.audio.RecorderHarness
import net.jbglenn.telltype.audio.Signal
import net.jbglenn.telltype.audio.Signals
import net.jbglenn.telltype.audio.orFail
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecorderInterruptionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val speech: Signal = Signals.tone(dbfs = -25.0)

    /** An interruption and the virtual time it arrived at. */
    private data class Heard(val cause: Interruption, val atMillis: Long)

    private fun TestScope.harness(signal: Signal = speech) =
        RecorderHarness(testScheduler, File(temp.root, "recordings"), signal)

    private fun TestScope.listen(h: RecorderHarness): MutableList<Heard> {
        val heard = mutableListOf<Heard>()
        backgroundScope.launch { h.recorder.interruptions.collect { heard += Heard(it, currentTime) } }
        return heard
    }

    @Test
    fun focusLossEndsCaptureAtOnceAndKeepsTheAudio() = runTest {
        val h = harness()
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(1.seconds)

        h.focus.lose()
        delay(2.seconds)

        assertEquals(listOf(Interruption.AUDIO_FOCUS_LOST), heard.map { it.cause })
        val source = h.sources.opened.single()
        assertTrue("microphone released before stop", source.closed)
        assertFalse(h.focus.held)
        assertFalse(h.monitor.watching)
        val clip = h.recorder.stop().orFail()
        assertTrue("only what came before the loss: ${clip.duration}", clip.duration <= 1.05.seconds)
        assertEquals(SpeechPresence.CLEAR, clip.speech)
        assertTrue(File(clip.path).isFile)
    }

    @Test
    fun aCallEndsCaptureAndKeepsTheAudio() = runTest {
        val h = harness()
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(800.milliseconds)

        h.monitor.fire(Interruption.CALL_STARTED)
        delay(100.milliseconds)

        assertEquals(listOf(Interruption.CALL_STARTED), heard.map { it.cause })
        assertEquals(SpeechPresence.CLEAR, h.recorder.stop().orFail().speech)
        assertEquals(1, h.focus.releases)
    }

    @Test
    fun screenOffEndsCaptureAndOnlyTheFirstInterruptionIsReported() = runTest {
        val h = harness()
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(500.milliseconds)

        h.monitor.fire(Interruption.SCREEN_OFF)
        h.focus.lose()
        delay(100.milliseconds)

        assertEquals(listOf(Interruption.SCREEN_OFF), heard.map { it.cause })
        h.recorder.stop().orFail()
    }

    @Test
    fun nothingIsReportedAfterStop() = runTest {
        val h = harness()
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(300.milliseconds)
        h.recorder.stop().orFail()

        h.monitor.fire(Interruption.SCREEN_OFF)
        h.focus.lose()
        delay(100.milliseconds)

        assertTrue(heard.isEmpty())
    }

    @Test
    fun allZeroInputIsReportedWithinHalfASecondAsMicrophoneInUse() = runTest {
        val h = harness(Signals.zeros)
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        assertEquals(listOf(Heard(Interruption.MICROPHONE_LOST, atMillis = 500)), heard)
        assertEquals(8_000L, h.sources.opened.single().position)
        assertEquals(Outcome.Err(MicrophoneInUse), h.recorder.stop())
        assertTrue("not recorded as silence", h.filesOnDisk().isEmpty())
    }

    @Test
    fun warmUpZerosAreNotMistakenForAMicrophoneInUse() = runTest {
        val h = harness(Signals.after(200, Signals.zeros, speech))
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        assertTrue(heard.isEmpty())
        assertEquals(SpeechPresence.CLEAR, h.recorder.stop().orFail().speech)
    }

    @Test
    fun aClientSilencedBeforeAnySoundIsMicrophoneInUse() = runTest {
        val h = harness(Signals.zeros)
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(100.milliseconds)

        h.sources.opened.single().silence()
        delay(100.milliseconds)

        assertEquals(listOf(Interruption.MICROPHONE_LOST), heard.map { it.cause })
        assertEquals(Outcome.Err(MicrophoneInUse), h.recorder.stop())
        assertTrue(h.filesOnDisk().isEmpty())
    }

    @Test
    fun theMicrophoneTakenAwayMidRecordingKeepsTheAudio() = runTest {
        val h = harness()
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(1.seconds)

        h.sources.opened.single().silence()
        delay(100.milliseconds)

        assertEquals(listOf(Interruption.MICROPHONE_LOST), heard.map { it.cause })
        assertEquals(SpeechPresence.CLEAR, h.recorder.stop().orFail().speech)
    }

    @Test
    fun inputGoingDigitallySilentAfterSpeechIsReportedAsLostAndKept() = runTest {
        val h = harness(Signals.after(1_000, speech, Signals.zeros))
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(3.seconds)

        assertEquals(listOf(Heard(Interruption.MICROPHONE_LOST, atMillis = 2_000)), heard)
        val clip = h.recorder.stop().orFail()
        assertEquals(SpeechPresence.CLEAR, clip.speech)
        assertEquals(2.seconds, clip.duration)
    }

    @Test
    fun aFailingReadMidRecordingKeepsTheAudio() = runTest {
        val h = harness()
        h.sources.failAtSample = 16_000
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        assertEquals(listOf(Interruption.MICROPHONE_LOST), heard.map { it.cause })
        assertEquals(1.seconds, h.recorder.stop().orFail().duration)
    }

    @Test
    fun aFailingFirstReadFailsTheStart() = runTest {
        val h = harness()
        h.sources.failAtSample = 0

        assertEquals(Outcome.Err(RecorderFailed), h.recorder.start(h.newRecording()))

        assertTrue(h.filesOnDisk().isEmpty())
        assertTrue(h.sources.opened.single().closed)
    }

    @Test
    fun storageFullWithSpeechKeepsThePartialRecording() = runTest {
        val h = harness()
        h.sinks.failAfterBytes = 32_000
        val heard = listen(h)
        h.recorder.start(h.newRecording()).orFail()
        delay(3.seconds)

        assertEquals(listOf(Interruption.RECORDING_FAILED), heard.map { it.cause })
        val clip = h.recorder.stop().orFail()
        assertEquals(1.seconds, clip.duration)
        assertEquals(SpeechPresence.CLEAR, clip.speech)
        ParsedWav.of(File(clip.path)).assertValidDictation(expectedDataBytes = 32_000)
    }

    @Test
    fun storageFullWithoutSpeechFailsAndDeletesThePartialFile() = runTest {
        val h = harness(Signals.noise(-80.0))
        h.sinks.failAfterBytes = 32_000
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        assertEquals(Outcome.Err(StorageFull), h.recorder.stop())
        assertTrue(h.filesOnDisk().isEmpty())
    }

    @Test
    fun anotherWriteFailureWithoutSpeechKeepsTheFileForRecovery() = runTest {
        val h = harness(Signals.noise(-80.0))
        h.sinks.failAfterBytes = 32_000
        h.sinks.failure = IOException("write failed: EIO (I/O error)")
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        assertEquals(Outcome.Err(StorageFailed), h.recorder.stop())
        val kept = h.filesOnDisk().single()
        ParsedWav.of(kept).assertValidDictation(expectedDataBytes = 32_000)
    }

    @Test
    fun storageFullBeforeAudioFlowsFailsTheStart() = runTest {
        val h = harness()
        h.sinks.failAfterBytes = 0

        assertEquals(Outcome.Err(StorageFull), h.recorder.start(h.newRecording()))

        assertTrue(h.filesOnDisk().isEmpty())
        assertFalse(h.focus.held)
    }
}
