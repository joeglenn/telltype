package net.jbglenn.telltype.audio.capture

import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.AudioLevel
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.settings.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AudioRecorderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val speech: Signal = Signals.tone(dbfs = -25.0)

    private fun TestScope.harness(signal: Signal = speech, settings: Settings = Settings.Defaults) =
        RecorderHarness(testScheduler, File(temp.root, "recordings"), signal, settings)

    private fun noFocusSettings() =
        Settings.Defaults.copy(dictation = Settings.Defaults.dictation.copy(pauseOtherAudio = false))

    @Test
    fun startReturnsOnceAudioFlowsAndStopDeliversTheClip() = runTest {
        val h = harness()
        val recording = h.newRecording()

        assertEquals(Outcome.Ok(Unit), h.recorder.start(recording))
        assertEquals("start returns after the first 20 ms buffer", 20L, currentTime)
        delay(1.seconds)
        val clip = h.recorder.stop().orFail()

        val file = File(clip.path)
        val samples = h.sources.opened.single().position
        assertEquals(checkNotNull(h.files.find(recording)).file.absolutePath, clip.path)
        assertEquals(recording, clip.recording)
        assertEquals(AudioFormat.Dictation, clip.format)
        assertEquals(SpeechPresence.CLEAR, clip.speech)
        assertEquals((samples / 16).milliseconds, clip.duration)
        assertTrue("about one second: ${clip.duration}", clip.duration in 1.seconds..1.1.seconds)
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = samples * 2)
        assertArrayEquals(Signals.samples(speech, samples.toInt()), ParsedWav.samplesOf(file))
        assertTrue(h.sources.opened.single().closed)
        assertFalse(h.files.isWriting(recording))
    }

    @Test
    fun theReadLoopRunsOnTheAudioDispatcherOnly() = runTest {
        val h = harness()
        h.recorder.start(h.newRecording()).orFail()
        delay(200.milliseconds)
        h.recorder.stop().orFail()

        assertEquals(setOf(h.dispatchers.audio), h.sources.readDispatchers)
    }

    @Test
    fun missingPermissionFailsBeforeOpeningTheMicrophoneAndLeavesNoFile() = runTest {
        val h = harness()
        h.permission.granted = false

        assertEquals(Outcome.Err(MicrophonePermissionMissing), h.recorder.start(h.newRecording()))

        assertTrue(h.sources.opened.isEmpty())
        assertEquals(0, h.focus.requests)
        assertTrue(h.filesOnDisk().isEmpty())
    }

    @Test
    fun aRecorderThatCannotInitialiseFailsAndReleasesEverything() = runTest {
        val h = harness()
        h.sources.openError = RecorderFailed

        assertEquals(Outcome.Err(RecorderFailed), h.recorder.start(h.newRecording()))

        assertTrue(h.filesOnDisk().isEmpty())
        assertEquals(1, h.focus.requests)
        assertEquals(1, h.focus.releases)
        assertFalse(h.monitor.watching)
    }

    @Test
    fun aMicrophoneHeldElsewhereAtOpenIsMicrophoneInUse() = runTest {
        val h = harness()
        h.sources.openError = MicrophoneInUse

        assertEquals(Outcome.Err(MicrophoneInUse), h.recorder.start(h.newRecording()))

        assertTrue(h.filesOnDisk().isEmpty())
        assertFalse(h.focus.held)
    }

    @Test
    fun startTimesOutWhenNoAudioArrives() = runTest {
        val h = harness()
        h.sources.stallReads = true

        assertEquals(Outcome.Err(RecorderFailed), h.recorder.start(h.newRecording()))

        assertEquals(AudioRecorder.START_TIMEOUT.inWholeMilliseconds, currentTime)
        assertTrue(h.sources.opened.single().closed)
        assertTrue(h.filesOnDisk().isEmpty())
        assertFalse(h.focus.held)
        assertFalse(h.monitor.watching)
    }

    @Test
    fun startTimesOutWhenOpeningHangs() = runTest {
        val h = harness()
        h.sources.openDelay = Duration.INFINITE

        assertEquals(Outcome.Err(RecorderFailed), h.recorder.start(h.newRecording()))

        assertEquals(AudioRecorder.START_TIMEOUT.inWholeMilliseconds, currentTime)
        assertTrue(h.filesOnDisk().isEmpty())
        assertFalse(h.focus.held)
    }

    @Test
    fun aSecondStartWhileRecordingIsRefusedAndTheFirstGoesOn() = runTest {
        val h = harness()
        val first = h.newRecording()
        val second = h.newRecording()
        h.recorder.start(first).orFail()

        assertEquals(Outcome.Err(RecorderFailed), h.recorder.start(second))
        assertEquals(Outcome.Err(RecorderFailed), h.recorder.start(first))
        delay(500.milliseconds)

        assertEquals(1, h.sources.opened.size)
        assertEquals(null, h.files.find(second))
        val clip = h.recorder.stop().orFail()
        assertEquals(first, clip.recording)
        assertTrue(File(clip.path).isFile)
    }

    @Test
    fun stopWithoutARecordingFails() = runTest {
        assertEquals(Outcome.Err(RecorderFailed), harness().recorder.stop())
    }

    @Test
    fun cancelLeavesNoFileAndNoRecorderBehind() = runTest {
        val h = harness()
        val levels = mutableListOf<AudioLevel>()
        backgroundScope.launch { h.recorder.levels.collect(levels::add) }
        h.recorder.start(h.newRecording()).orFail()
        delay(300.milliseconds)

        h.recorder.cancel()
        val heardWhileRecording = levels.size
        delay(1.seconds)

        assertTrue(h.filesOnDisk().isEmpty())
        val source = h.sources.opened.single()
        assertTrue(source.closed)
        assertEquals(0, source.readsAfterClose)
        assertFalse(h.focus.held)
        assertFalse(h.monitor.watching)
        assertTrue(heardWhileRecording > 0)
        assertEquals("levels are silent after cancel", heardWhileRecording, levels.size)
        assertEquals(Outcome.Err(RecorderFailed), h.recorder.stop())
        h.recorder.start(h.newRecording()).orFail()
        h.recorder.cancel()
        assertTrue(h.filesOnDisk().isEmpty())
    }

    @Test
    fun cancelWhileIdleDoesNothing() = runTest {
        val h = harness()
        val kept = h.newRecording()

        h.recorder.cancel()

        assertTrue(h.files.find(kept) != null)
    }

    @Test
    fun aCancelledStartLeavesNothingBehind() = runTest {
        val h = harness()
        h.sources.openDelay = 500.milliseconds
        val recording = h.newRecording()

        val start = async(start = CoroutineStart.UNDISPATCHED) { h.recorder.start(recording) }
        delay(100.milliseconds)
        start.cancel()
        delay(1.seconds)

        assertTrue(start.isCancelled)
        assertTrue(h.filesOnDisk().isEmpty())
        assertFalse(h.focus.held)
        assertFalse(h.monitor.watching)
        assertTrue(h.sources.opened.isEmpty() || h.sources.opened.single().closed)
        h.recorder.start(h.newRecording()).orFail()
        h.recorder.cancel()
    }

    @Test
    fun focusIsHeldForTheRecordingAndReleasedOnStop() = runTest {
        val h = harness()
        h.recorder.start(h.newRecording()).orFail()

        assertTrue(h.focus.held)
        assertTrue(h.monitor.watching)
        delay(500.milliseconds)
        h.recorder.stop().orFail()

        assertEquals(1, h.focus.requests)
        assertEquals(1, h.focus.releases)
        assertFalse(h.monitor.watching)
    }

    @Test
    fun focusIsReleasedOnCancel() = runTest {
        val h = harness()
        h.recorder.start(h.newRecording()).orFail()

        h.recorder.cancel()

        assertEquals(1, h.focus.releases)
    }

    @Test
    fun focusIsReleasedWhenStopFails() = runTest {
        val h = harness(Signals.noise(-80.0))
        h.recorder.start(h.newRecording()).orFail()
        delay(1.seconds)

        assertEquals(Outcome.Err(NoSpeech), h.recorder.stop())

        assertEquals(1, h.focus.releases)
    }

    @Test
    fun focusIsNotTakenWhenTheSettingIsOff() = runTest {
        val h = harness(settings = noFocusSettings())

        h.recorder.start(h.newRecording()).orFail()
        delay(500.milliseconds)
        h.recorder.stop().orFail()

        assertEquals(0, h.focus.requests)
    }

    @Test
    fun aRefusedFocusRequestDoesNotStopTheRecording() = runTest {
        val h = harness()
        h.focus.grants = false

        h.recorder.start(h.newRecording()).orFail()
        delay(500.milliseconds)

        assertEquals(SpeechPresence.CLEAR, h.recorder.stop().orFail().speech)
    }

    @Test
    fun aSilentRecordingIsNoSpeechAndIsDeleted() = runTest {
        val h = harness(Signals.noise(-80.0))
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        assertEquals(Outcome.Err(NoSpeech), h.recorder.stop())

        assertTrue(h.filesOnDisk().isEmpty())
    }

    @Test
    fun aNearSilentRecordingIsDeliveredAsFaint() = runTest {
        val h = harness(Signals.noise(-58.0))
        h.recorder.start(h.newRecording()).orFail()
        delay(2.seconds)

        val clip = h.recorder.stop().orFail()

        assertEquals(SpeechPresence.FAINT, clip.speech)
        assertTrue(File(clip.path).isFile)
    }

    @Test
    fun levelsFollowTheSignalAtMostSixtyTimesPerSecond() = runTest {
        val h = harness(Signals.dc(16_384))
        h.sources.samplesPerRead = 80
        val levels = mutableListOf<AudioLevel>()
        backgroundScope.launch { h.recorder.levels.collect(levels::add) }

        h.recorder.start(h.newRecording()).orFail()
        delay(1.seconds)
        val inOneSecond = levels.size
        h.recorder.stop()
        delay(1.seconds)

        assertTrue("$inOneSecond levels in one second", inOneSecond in 50..60)
        assertTrue(levels.all { it.value == 0.5f })
        assertEquals("silent after stop", inOneSecond, levels.size)
    }

    @Test
    fun levelsAreSilentWhileIdle() = runTest {
        val h = harness()
        val levels = mutableListOf<AudioLevel>()
        backgroundScope.launch { h.recorder.levels.collect(levels::add) }

        delay(5.seconds)

        assertTrue(levels.isEmpty())
    }

    @Test
    fun theWholeRecordingReusesOneReadBuffer() = runTest {
        val h = harness()
        h.recorder.start(h.newRecording()).orFail()
        delay(3.seconds)
        h.recorder.stop().orFail()

        val buffer = h.sources.buffers.single()
        assertSame(buffer, h.sources.buffers.first())
        assertEquals(CaptureRun.BUFFER_SAMPLES, buffer.size)
    }
}
