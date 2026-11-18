package net.jbglenn.telltype.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import net.jbglenn.telltype.audio.capture.MicrophoneOverride
import net.jbglenn.telltype.audio.capture.PcmSource
import net.jbglenn.telltype.audio.capture.PcmSourceFactory
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.RecorderFailed

/**
 * Debug builds: a microphone that plays a bundled [SampleClip] in real time, then the low noise of a quiet room
 * (an exact-zero signal would look like a lost microphone), while [DebugSwitches.clipMicrophone] is on. Otherwise
 * the real microphone. The clip is the one named in the clip marker file ([SampleClip.assetFor]), read at each
 * recording start; the Phoebe clip when the marker names none. The recorder, level meter, speech detector and WAV writer run unchanged on these samples.
 */
internal class ClipMicrophone @Inject constructor(
    @ApplicationContext private val context: Context,
    private val switches: DebugSwitches,
    private val logger: Logger,
) : MicrophoneOverride {
    override fun wrap(real: PcmSourceFactory): PcmSourceFactory = object : PcmSourceFactory {
        override suspend fun open(format: AudioFormat, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError> =
            if (switches.clipMicrophone()) openClip(format) else real.open(format, onSilenced)
    }

    private suspend fun openClip(format: AudioFormat): Outcome<PcmSource, CaptureError> {
        val asset = SampleClip.assetFor(switches.markerText(DebugSwitches.CLIP_MICROPHONE_MARKER))
        val clip = try {
            SampleClip.load(context.assets, asset)
        } catch (_: IOException) {
            null
        }
        return if (clip == null || clip.sampleRateHz != format.sampleRateHz || format.channelCount != 1) {
            Outcome.Err(RecorderFailed)
        } else {
            // The default clip's name is a word of its transcript, which logs (and crash reports) never hold.
            val shown = if (asset == SampleClip.ASSET) "the bundled sample" else asset
            logger.info(TAG) { "clip microphone: playing $shown" }
            Outcome.Ok(ClipSource(clip))
        }
    }

    /** Delivers the clip at its own pace: each read waits as long as the samples it returns last. */
    private class ClipSource(private val clip: SampleClip) : PcmSource {
        private var position = 0
        private val noise = Random(NOISE_SEED)

        override suspend fun read(buffer: ShortArray): Int {
            delay((buffer.size * MILLIS_PER_SECOND / clip.sampleRateHz).milliseconds)
            for (index in buffer.indices) {
                buffer[index] = if (position < clip.samples.size) {
                    clip.samples[position++]
                } else {
                    noise.nextInt(-NOISE_PEAK, NOISE_PEAK + 1).toShort()
                }
            }
            return buffer.size
        }

        override fun close() = Unit
    }

    private companion object {
        val TAG = LogTag("ClipMicrophone")
        const val MILLIS_PER_SECOND = 1_000L
        const val NOISE_PEAK = 8
        const val NOISE_SEED = 42
    }
}
