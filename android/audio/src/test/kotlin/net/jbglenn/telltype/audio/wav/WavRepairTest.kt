package net.jbglenn.telltype.audio.wav

import net.jbglenn.telltype.audio.ParsedWav
import net.jbglenn.telltype.audio.Signals
import net.jbglenn.telltype.core.model.audio.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavRepairTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val format = AudioFormat.Dictation

    @Test
    fun aTruncatedRecordingIsRepairedFromItsLength() {
        val file = temp.newFile("crashed.wav")
        val writer = WavFileWriter.openWriter(file, format)
        val samples = Signals.samples(Signals.tone(-20.0), 16_000)
        writer.write(samples, samples.size)
        assertNull(writer.close())
        file.appendBytes(byteArrayOf(0x11))
        assertEquals(0L, ParsedWav.of(file).dataSize)

        assertEquals(32_000L, WavRepair.repair(file, format))

        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 32_000)
        assertArrayEquals(samples, ParsedWav.samplesOf(file))
    }

    @Test
    fun aCorruptHeaderIsRewritten() {
        val file = temp.newFile("corrupt.wav")
        file.writeBytes(ByteArray(44) { 0x55 } + ByteArray(100))

        assertEquals(100L, WavRepair.repair(file, format))

        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 100)
    }

    @Test
    fun aFileShorterThanAHeaderBecomesAnEmptyWav() {
        val file = temp.newFile("stub.wav")
        file.writeBytes(byteArrayOf(0x52, 0x49))

        assertEquals(0L, WavRepair.repair(file, format))

        ParsedWav.of(file).assertValidDictation(expectedDataBytes = 0)
    }

    @Test
    fun aValidFileIsNotTouched() {
        val file = temp.newFile("valid.wav")
        val writer = WavFileWriter.openWriter(file, format)
        writer.write(ShortArray(100) { 7 }, 100)
        writer.finalise()
        file.setLastModified(1_000_000_000_000)
        val before = file.readBytes()

        assertEquals(200L, WavRepair.repair(file, format))

        assertArrayEquals(before, file.readBytes())
        assertEquals(1_000_000_000_000, file.lastModified())
    }

    @Test
    fun samplesAreReadBackInChunksOfBoundedSize() {
        val file = temp.newFile("long.wav")
        val writer = WavFileWriter.openWriter(file, format)
        val samples = Signals.samples(Signals.noise(-30.0), 50_000)
        writer.write(samples, samples.size)
        val dataBytes = writer.finalise()
        val read = mutableListOf<Short>()
        var largestChunk = 0

        WavSamples.forEachChunk(file, dataBytes) { chunk, count ->
            largestChunk = maxOf(largestChunk, count)
            for (index in 0 until count) read += chunk[index]
        }

        assertArrayEquals(samples, read.toShortArray())
        assertEquals(8_192, largestChunk)
    }
}
