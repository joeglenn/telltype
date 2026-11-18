package net.jbglenn.telltype.audio.wav

import java.nio.ByteBuffer
import java.nio.ByteOrder
import net.jbglenn.telltype.core.model.audio.AudioFormat

/**
 * The canonical 44-byte RIFF/WAVE header for linear PCM: `RIFF` chunk, a 16-byte `fmt ` chunk and the `data`
 * chunk header. Every recording file starts with it, so the audio always begins at [SIZE] and the data length
 * can be recomputed from the file length alone (crash repair).
 */
internal object WavHeader {
    private const val RIFF_PREAMBLE = 8

    /** Header length in bytes; the first sample starts here. */
    const val SIZE: Int = 44

    /** The largest data chunk a 32-bit RIFF size field can describe. */
    const val MAX_DATA_BYTES: Long = 0xFFFF_FFFFL - (SIZE - RIFF_PREAMBLE)
    private const val FMT_CHUNK_SIZE = 16
    private const val PCM_FORMAT_TAG: Short = 1
    private const val BITS_PER_BYTE = 8

    /** Bytes of one sample frame (all channels) in [format]. */
    fun blockAlign(format: AudioFormat): Int = format.channelCount * format.bitsPerSample / BITS_PER_BYTE

    /**
     * Writes the header for [dataBytes] bytes of [format] audio into [target] at its position, little-endian,
     * advancing the position by [SIZE]. [target] must have [SIZE] bytes remaining.
     */
    fun write(target: ByteBuffer, format: AudioFormat, dataBytes: Long) {
        require(dataBytes in 0..MAX_DATA_BYTES) { "data length $dataBytes does not fit a WAV header" }
        val align = blockAlign(format)
        target.order(ByteOrder.LITTLE_ENDIAN)
        target.put(ascii("RIFF"))
        target.putInt((dataBytes + SIZE - RIFF_PREAMBLE).toInt())
        target.put(ascii("WAVE"))
        target.put(ascii("fmt "))
        target.putInt(FMT_CHUNK_SIZE)
        target.putShort(PCM_FORMAT_TAG)
        target.putShort(format.channelCount.toShort())
        target.putInt(format.sampleRateHz)
        target.putInt(format.sampleRateHz * align)
        target.putShort(align.toShort())
        target.putShort(format.bitsPerSample.toShort())
        target.put(ascii("data"))
        target.putInt(dataBytes.toInt())
    }

    /** A fresh header for [dataBytes] bytes of [format] audio. */
    fun bytes(format: AudioFormat, dataBytes: Long): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE)
        write(buffer, format, dataBytes)
        return buffer.array()
    }

    /**
     * The audio data length a file of [fileLength] bytes holds: everything after the header, rounded down to whole
     * sample frames (a crash can leave half a sample at the end), capped at [MAX_DATA_BYTES].
     */
    fun dataBytesFor(fileLength: Long, format: AudioFormat): Long {
        val align = blockAlign(format)
        val raw = (fileLength - SIZE).coerceIn(0, MAX_DATA_BYTES)
        return raw - raw % align
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
