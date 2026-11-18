package net.jbglenn.telltype.support

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration.Companion.milliseconds
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bundled self-test sample is a small 16 kHz mono 16-bit WAV and is copied as a clip of the right length. */
// A plain Application: TelltypeApp would start the whole app (runtime, WorkManager) around this test.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SelfTestSampleAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = context.assets.open(SelfTestSampleAsset.ASSET).use { it.readBytes() }

    @Test
    fun sampleIsASmallDictationFormatWav() {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals("mono", 1, header.getShort(22).toInt())
        assertEquals("16 kHz", AudioFormat.Dictation.sampleRateHz, header.getInt(24))
        assertEquals("16-bit", 16, header.getShort(34).toInt())
        assertTrue("under 150 KB: ${bytes.size}", bytes.size < MAX_BYTES)
    }

    @Test
    fun copyGivesAClipOfTheSampleLength() {
        val clip = checkNotNull(SelfTestSampleAsset.copyTo(context.assets, context.cacheDir, SessionId("s1"))) {
            "the sample could not be copied"
        }
        val copied = File(clip.path).readBytes()
        assertArrayEquals(bytes, copied)
        val expected = ((bytes.size - SelfTestSampleAsset.WAV_HEADER_BYTES) * 1_000 / BYTES_PER_SECOND).milliseconds
        assertEquals(expected, clip.duration)
        assertTrue("a spoken sentence of 2 to 4 s: ${clip.duration}", clip.duration.inWholeMilliseconds in 2_000..4_000)
        assertEquals(SessionId("s1"), clip.session)
    }

    private companion object {
        const val MAX_BYTES = 150 * 1024
        const val BYTES_PER_SECOND = 16_000 * 2
    }
}
