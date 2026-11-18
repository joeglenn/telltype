package net.jbglenn.telltype.audio.capture

import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.audio.ParsedWav
import net.jbglenn.telltype.audio.RecorderHarness
import net.jbglenn.telltype.audio.Signals
import net.jbglenn.telltype.audio.orFail
import net.jbglenn.telltype.audio.wav.WavSamples
import net.jbglenn.telltype.core.model.audio.SpeechPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The recorder survives the longest recording the server accepts (6 minutes, Appendix C) with bounded memory: the
 * audio streams to disk as it arrives (the file holds half of it at the half-way point), every read goes into the
 * same 20 ms buffer, and the analysers keep only counters.
 */
class LongRecordingTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sixMinutesStreamToDiskThroughOneBuffer() = runTest(timeout = 2.minutes) {
        val speech = Signals.mix(
            Signals.bursts(Signals.tone(dbfs = -28.0, hz = 220.0), onMillis = 300, periodMillis = 700),
            Signals.noise(dbfs = -70.0),
        )
        val h = RecorderHarness(testScheduler, File(temp.root, "recordings"), speech)
        val recording = h.newRecording()
        h.recorder.start(recording).orFail()
        val file = checkNotNull(h.files.find(recording)).file

        delay(3.minutes)
        val halfWay = file.length()
        delay(3.minutes)
        val clip = h.recorder.stop().orFail()

        val samples = h.sources.opened.single().position
        assertTrue("streamed while recording: $halfWay bytes at 3 minutes", halfWay >= 44 + 3 * 60 * 32_000L)
        assertTrue("six minutes: ${clip.duration}", clip.duration in 6.minutes..6.minutes + 1.seconds)
        assertEquals(SpeechPresence.CLEAR, clip.speech)
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = samples * 2)
        assertEquals("one read buffer for the whole recording", 1, h.sources.buffers.size)
        assertEquals(CaptureRun.BUFFER_SAMPLES, h.sources.buffers.single().size)
        var index = 0L
        var mismatches = 0
        WavSamples.forEachChunk(file, samples * 2) { chunk, count ->
            for (i in 0 until count) if (chunk[i] != speech.sample(index + i)) mismatches++
            index += count
        }
        assertEquals(samples, index)
        assertEquals("every sample on disk matches the signal", 0, mismatches)
    }
}
