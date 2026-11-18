package net.jbglenn.telltype.audio.wav

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.FileChannel
import javax.inject.Inject
import net.jbglenn.telltype.core.model.audio.AudioEncoding
import net.jbglenn.telltype.core.model.audio.AudioFormat

/**
 * Streams 16-bit PCM into a WAV file. The file always starts with a [WavHeader]; its size fields are fixed by
 * [finalise], and a file left behind by a crash is repaired from its length by [WavRepair]. Samples are converted
 * into one reusable direct buffer, so a write allocates nothing. Every [SYNC_INTERVAL_BYTES] the data is forced to
 * storage, which bounds what a power loss (not only a process crash) can take.
 */
internal class WavFileWriter private constructor(
    private val file: RandomAccessFile,
    private val format: AudioFormat,
    private var dataBytes: Long,
) : RecordingSink {
    private val channel: FileChannel = file.channel
    private var bytes: ByteBuffer = directBuffer(INITIAL_BUFFER_BYTES)
    private var unsyncedBytes = 0L
    private var closed = false

    @Synchronized
    override fun write(samples: ShortArray, count: Int) {
        if (closed) throw ClosedChannelException()
        require(count in 0..samples.size) { "count $count outside 0..${samples.size}" }
        val needed = count * BYTES_PER_SAMPLE
        if (needed > WavHeader.MAX_DATA_BYTES - dataBytes) throw IOException("recording exceeds the WAV size limit")
        if (bytes.capacity() < needed) bytes = directBuffer(needed)
        bytes.clear()
        for (index in 0 until count) bytes.putShort(samples[index])
        bytes.flip()
        while (bytes.hasRemaining()) channel.write(bytes)
        dataBytes += needed
        unsyncedBytes += needed
        if (unsyncedBytes >= SYNC_INTERVAL_BYTES) {
            channel.force(false)
            unsyncedBytes = 0
        }
    }

    @Synchronized
    override fun finalise(): Long {
        if (closed) return dataBytes
        closed = true
        try {
            val header = ByteBuffer.allocate(WavHeader.SIZE)
            WavHeader.write(header, format, dataBytes)
            header.flip()
            var position = 0L
            while (header.hasRemaining()) position += channel.write(header, position)
            channel.force(true)
        } catch (error: IOException) {
            closeHandle(file)?.let(error::addSuppressed)
            throw error
        }
        closeHandle(file)?.let { throw it }
        return dataBytes
    }

    @Synchronized
    override fun close(): IOException? {
        if (closed) return null
        closed = true
        return closeHandle(file)
    }

    /** Opens recording files as [WavFileWriter]s. */
    class Factory @Inject constructor() : RecordingSinkFactory {
        override fun open(file: File, format: AudioFormat): RecordingSink = openWriter(file, format)
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2

        /** Force the data to storage after this many bytes: five seconds of dictation audio. */
        const val SYNC_INTERVAL_BYTES: Long = 5L * 16_000 * BYTES_PER_SAMPLE
        private const val SUPPORTED_BITS = 16
        private const val INITIAL_BUFFER_BYTES = 4_096

        /**
         * Opens [file] (which must exist) for appending. A file shorter than a header gets a fresh one; a trailing
         * half sample left by a crash is cut off; existing audio is kept and new samples follow it.
         */
        @Throws(IOException::class)
        fun openWriter(file: File, format: AudioFormat): WavFileWriter {
            require(format.encoding == AudioEncoding.WAV && format.bitsPerSample == SUPPORTED_BITS) {
                "only 16-bit PCM WAV is written"
            }
            if (!file.isFile) throw IOException("recording file does not exist")
            val handle = RandomAccessFile(file, "rw")
            try {
                val length = handle.length()
                if (length < WavHeader.SIZE) {
                    handle.seek(0)
                    handle.write(WavHeader.bytes(format, 0))
                }
                val data = WavHeader.dataBytesFor(handle.length(), format)
                handle.setLength(WavHeader.SIZE + data)
                handle.channel.position(WavHeader.SIZE + data)
                return WavFileWriter(handle, format, data)
            } catch (error: IOException) {
                handle.close()
                throw error
            }
        }

        private fun closeHandle(handle: RandomAccessFile): IOException? = try {
            handle.close()
            null
        } catch (error: IOException) {
            error
        }

        private fun directBuffer(capacity: Int): ByteBuffer =
            ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
    }
}
