package net.jbglenn.telltype.debug

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.audio.capture.PcmSource
import net.jbglenn.telltype.audio.capture.PcmSourceFactory
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The debug build's switches, clip microphone and fake engines (marker files in `no_backup/debug`). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DebugFakesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = FakeSettingsRepository()

    private fun TestScope.switches() =
        DebugSwitches(context, settings, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))

    private fun marker(name: String, text: String = "on\n") =
        File(context.noBackupFilesDir, name).apply { parentFile?.mkdirs() }.writeText(text)

    @After
    fun removeMarkers() {
        listOf(DebugSwitches.CLIP_MICROPHONE_MARKER, DebugSwitches.FAKE_ENGINES_MARKER, FakeFaults.FAULTS_MARKER)
            .forEach { File(context.noBackupFilesDir, it).delete() }
    }

    /** The real microphone: records that it was asked and answers "in use". */
    private class RealMicrophone : PcmSourceFactory {
        var opened = 0

        override suspend fun open(format: AudioFormat, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError> {
            opened++
            return Outcome.Err(MicrophoneInUse)
        }
    }

    @Test
    fun `without markers or the setting everything is real`() = runTest {
        val switches = switches()
        assertEquals(false, switches.fakeEngines())
        assertEquals(false, switches.clipMicrophone())
        val real = RealMicrophone()
        val opened = ClipMicrophone(context, switches, RecordingLogger()).wrap(real).open(AudioFormat.Dictation) {}
        assertEquals(Outcome.Err(MicrophoneInUse), opened)
        assertEquals(1, real.opened)
    }

    @Test
    fun `the fake-engines marker and the setting each switch the fakes and the clip microphone on`() = runTest {
        marker(DebugSwitches.FAKE_ENGINES_MARKER)
        assertTrue(switches().fakeEngines())
        assertTrue(switches().clipMicrophone())
        removeMarkers()
        settings.update { it.copy(advanced = it.advanced.copy(useFakeEngines = true)) }
        assertTrue(switches().fakeEngines())
    }

    @Test
    fun `the clip microphone plays the clip in real time, then quiet-room noise`() = runTest {
        marker(DebugSwitches.CLIP_MICROPHONE_MARKER)
        val real = RealMicrophone()
        val opened = ClipMicrophone(context, switches(), RecordingLogger()).wrap(real).open(AudioFormat.Dictation) {}
        val source = (opened as Outcome.Ok).value
        assertEquals(0, real.opened)
        val clip = checkNotNull(SampleClip.load(context.assets))
        val buffer = ShortArray(BUFFER)
        val start = testScheduler.currentTime
        assertEquals(BUFFER, source.read(buffer))
        assertEquals("one buffer of 16 kHz audio takes its own length", BUFFER_MS, testScheduler.currentTime - start)
        assertTrue("the clip's first samples", buffer.contentEquals(clip.samples.copyOf(BUFFER)))
        repeat(clip.samples.size / BUFFER + 1) { source.read(buffer) }
        assertTrue("noise after the clip, within ±8", buffer.all { it in -NOISE..NOISE })
        assertTrue("not digital silence", buffer.any { it != 0.toShort() })
    }

    @Test
    fun `the clip microphone refuses a format the clip does not have`() = runTest {
        marker(DebugSwitches.CLIP_MICROPHONE_MARKER)
        val stereo = AudioFormat.Dictation.copy(channelCount = 2)
        val opened = ClipMicrophone(context, switches(), RecordingLogger()).wrap(RealMicrophone()).open(stereo) {}
        assertEquals(Outcome.Err(RecorderFailed), opened)
    }

    @Test
    fun `the fake speech engine answers the clip's transcript after 600 ms, or EmptyTranscript when scripted`() =
        runTest {
            val engine = FakeSpeechToTextEngine(FakeFaults(switches()))
            val answer = async { engine.transcribe(Fixtures.audioClip(), SttHints(emptyList())) }
            advanceTimeBy(599.milliseconds)
            assertEquals(false, answer.isCompleted)
            advanceTimeBy(2.milliseconds)
            assertEquals(SampleClip.TRANSCRIPT, (answer.await() as Outcome.Ok).value.text)
            marker(FakeFaults.FAULTS_MARKER, "cleanup=slow\nstt=empty\n")
            assertEquals(Outcome.Err(EmptyTranscript), engine.transcribe(Fixtures.audioClip(), SttHints(emptyList())))
        }

    @Test
    fun `the fake cleaner adds the final full stop after 400 ms, or answers after a minute when slow`() = runTest {
        val cleaner = FakeTextCleaner(FakeFaults(switches()))
        val request = CleanupRequest(
            session = SessionId("session-1"),
            transcript = "hello world",
            tone = Tone.AS_SPOKEN,
            category = AppCategory.EMAIL,
            dictionary = emptyList(),
            contextBefore = "",
            fieldHint = null,
        )
        val start = testScheduler.currentTime
        val cleaned = (cleaner.clean(request) as Outcome.Ok).value
        assertEquals("hello world.", cleaned.text)
        assertEquals(CleanupStatus.APPLIED, cleaned.status)
        assertEquals(400L, testScheduler.currentTime - start)
        marker(FakeFaults.FAULTS_MARKER, "cleanup=slow\n")
        val slowStart = testScheduler.currentTime
        cleaner.clean(request)
        assertEquals(60.seconds.inWholeMilliseconds, testScheduler.currentTime - slowStart)
    }

    @Test
    fun `the switching engines use the fake while the switch is on, read per call`() = runTest {
        val switches = switches()
        val fake = FakeSpeechToTextEngine(FakeFaults(switches))
        val real = net.jbglenn.telltype.testing.FakeSpeechToTextEngine()
        val engine = SwitchingSpeechToTextEngine(real, fake, switches)
        engine.transcribe(Fixtures.audioClip(), SttHints(emptyList()))
        assertEquals(1, real.transcribeCall.calls.size)
        marker(DebugSwitches.FAKE_ENGINES_MARKER)
        val faked = engine.transcribe(Fixtures.audioClip(), SttHints(emptyList()))
        assertEquals(1, real.transcribeCall.calls.size)
        assertEquals(SampleClip.TRANSCRIPT, (faked as Outcome.Ok).value.text)
    }

    private companion object {
        const val BUFFER = 1_600
        const val BUFFER_MS = 100L
        const val NOISE: Short = 8
    }
}
