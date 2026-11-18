package net.jbglenn.telltype.audio.wav

import java.io.File
import java.nio.channels.ClosedChannelException
import net.jbglenn.telltype.audio.ParsedWav
import net.jbglenn.telltype.audio.Signals
import net.jbglenn.telltype.core.model.audio.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavFileWriterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val format = AudioFormat.Dictation

    private fun emptyFile(): File = temp.newFile("rec.wav")

    @Test
    fun zeroBuffersGiveAValidEmptyWav() {
        val file = emptyFile()
        val writer = WavFileWriter.openWriter(file, format)

        assertEquals(0L, writer.finalise())

        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 0)
    }

    @Test
    fun oneBufferIsWrittenLittleEndianAfterTheHeader() {
        val file = emptyFile()
        val samples = Signals.samples(Signals.noise(-10.0), 320)
        val writer = WavFileWriter.openWriter(file, format)

        writer.write(samples, samples.size)

        assertEquals(640L, writer.finalise())
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 640)
        assertArrayEquals(samples, ParsedWav.samplesOf(file))
        val bytes = file.readBytes()
        assertEquals((samples[0].toInt() and 0xFF).toByte(), bytes[44])
        assertEquals((samples[0].toInt() shr 8).toByte(), bytes[45])
    }

    @Test
    fun manyBuffersAndPartialBuffersAddUp() {
        val file = emptyFile()
        val signal = Signals.tone(-20.0)
        val writer = WavFileWriter.openWriter(file, format)
        val buffer = ShortArray(320)
        var written = 0L
        repeat(1_000) { round ->
            val count = if (round % 3 == 0) 160 else 320
            for (index in 0 until count) buffer[index] = signal.sample(written + index)
            writer.write(buffer, count)
            written += count
        }

        assertEquals(written * 2, writer.finalise())

        ParsedWav.of(file).assertValidDictation(expectedDataBytes = written * 2)
        assertArrayEquals(Signals.samples(signal, written.toInt()), ParsedWav.samplesOf(file))
    }

    @Test
    fun finaliseIsIdempotentAndLaterWritesFail() {
        val file = emptyFile()
        val writer = WavFileWriter.openWriter(file, format)
        writer.write(ShortArray(10) { 1 }, 10)

        assertEquals(20L, writer.finalise())
        assertEquals(20L, writer.finalise())
        assertNull(writer.close())

        assertThrows(ClosedChannelException::class.java) { writer.write(ShortArray(1), 1) }
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 20)
    }

    @Test
    fun reopeningKeepsTheAudioAndCutsAHalfSample() {
        val file = emptyFile()
        val first = WavFileWriter.openWriter(file, format)
        first.write(shortArrayOf(1, 2, 3), 3)
        assertNull(first.close())
        file.appendBytes(byteArrayOf(0x7F))

        val second = WavFileWriter.openWriter(file, format)
        second.write(shortArrayOf(4), 1)

        assertEquals(8L, second.finalise())
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), ParsedWav.samplesOf(file))
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 8)
    }

    @Test
    fun aFileShorterThanAHeaderGetsAFreshOne() {
        val file = emptyFile()
        file.writeBytes(byteArrayOf(1, 2, 3))

        val writer = WavFileWriter.openWriter(file, format)
        writer.write(shortArrayOf(9), 1)

        assertEquals(2L, writer.finalise())
        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 2)
    }

    @Test
    fun aMissingFileIsAnIoError() {
        assertThrows(java.io.IOException::class.java) {
            WavFileWriter.openWriter(File(temp.root, "missing.wav"), format)
        }
    }
}
