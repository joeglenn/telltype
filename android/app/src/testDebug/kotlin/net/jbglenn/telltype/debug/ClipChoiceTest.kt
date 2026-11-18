package net.jbglenn.telltype.debug

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.audio.capture.PcmSource
import net.jbglenn.telltype.audio.capture.PcmSourceFactory
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Package V1: the clip marker file names the clip the debug microphone plays (the feature tests' clips). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ClipChoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val marker = File(context.noBackupFilesDir, DebugSwitches.CLIP_MICROPHONE_MARKER)

    @After
    fun removeMarker() {
        marker.delete()
    }

    @Test
    fun `the marker's content names the asset, anything else means the Phoebe clip`() {
        val cases = mapOf(
            null to SampleClip.ASSET,
            "" to SampleClip.ASSET,
            "on\n" to SampleClip.ASSET,
            "snippet\n" to "sample/snippet.wav",
            "command-list" to "sample/command-list.wav",
            "../secret" to SampleClip.ASSET,
            "Tone" to SampleClip.ASSET,
        )
        cases.forEach { (content, asset) -> assertEquals("'$content'", asset, SampleClip.assetFor(content)) }
    }

    @Test
    fun `every feature clip is bundled as 16 kHz mono 16-bit speech`() {
        FEATURE_CLIPS.forEach { name ->
            val clip = SampleClip.load(context.assets, SampleClip.assetFor(name))
            assertNotNull(name, clip)
            checkNotNull(clip)
            assertEquals(name, AudioFormat.Dictation.sampleRateHz, clip.sampleRateHz)
            assertTrue("$name holds at least a second", clip.samples.size >= clip.sampleRateHz)
        }
    }

    @Test
    fun `the microphone plays the clip the marker names`() = runTest {
        marker.apply { parentFile?.mkdirs() }.writeText("snippet\n")
        val switches = DebugSwitches(
            context,
            FakeSettingsRepository(),
            TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        )
        val opened = ClipMicrophone(context, switches, RecordingLogger()).wrap(Unused).open(AudioFormat.Dictation) {}
        val source = (opened as Outcome.Ok).value
        val buffer = ShortArray(BUFFER)
        source.read(buffer)
        val clip = checkNotNull(SampleClip.load(context.assets, "sample/snippet.wav"))
        assertTrue("the snippet clip's first samples", buffer.contentEquals(clip.samples.copyOf(BUFFER)))
    }

    /** The real microphone, which this test never opens. */
    private object Unused : PcmSourceFactory {
        override suspend fun open(format: AudioFormat, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError> =
            Outcome.Err(MicrophoneInUse)
    }

    private companion object {
        const val BUFFER = 1_600

        /** The clips `android/scripts/make-feature-clips.py` writes. */
        val FEATURE_CLIPS = listOf("tone", "dictionary", "snippet") + listOf(
            "list", "shorter", "polite", "formal", "grammar", "casual", "question", "capitals", "greeting", "last",
        ).map { "command-$it" }
    }
}
