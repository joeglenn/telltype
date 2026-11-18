package net.jbglenn.telltype.audio.wav

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the samples of a recording file in chunks, for the speech check of a recovered recording. */
internal object WavSamples {
    private const val CHUNK_SAMPLES = 8_192
    private const val BYTES_PER_SAMPLE = 2

    /**
     * Calls [consume] with consecutive chunks of the first [dataBytes] bytes of audio in [file] (16-bit, after the
     * header). The chunk array is reused between calls; memory use does not depend on the file length.
     *
     * @throws IOException when the file cannot be read.
     */
    @Throws(IOException::class)
    fun forEachChunk(file: File, dataBytes: Long, consume: (samples: ShortArray, count: Int) -> Unit) {
        val samples = ShortArray(CHUNK_SAMPLES)
        val bytes = ByteBuffer.allocateDirect(CHUNK_SAMPLES * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        RandomAccessFile(file, "r").use { handle ->
            val channel = handle.channel
            var position = WavHeader.SIZE.toLong()
            val end = WavHeader.SIZE + dataBytes
            while (position < end) {
                bytes.clear()
                bytes.limit(minOf(bytes.capacity().toLong(), end - position).toInt())
                val read = channel.read(bytes, position)
                if (read <= 0) throw IOException("recording file ended early")
                position += read
                bytes.flip()
                val count = bytes.remaining() / BYTES_PER_SAMPLE
                position -= bytes.remaining() % BYTES_PER_SAMPLE
                for (index in 0 until count) samples[index] = bytes.getShort()
                consume(samples, count)
            }
        }
    }
}
