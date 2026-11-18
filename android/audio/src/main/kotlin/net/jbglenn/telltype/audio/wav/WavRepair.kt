package net.jbglenn.telltype.audio.wav

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import net.jbglenn.telltype.core.model.audio.AudioFormat

/**
 * Makes a recording file a valid WAV file again after a crash: the size fields are recomputed from the file length,
 * a trailing half sample is cut off, and a file too short to hold a header gets an empty one. Idempotent: a file that
 * is already valid is not written to (its modification time stays, which the oldest-first eviction relies on).
 */
internal object WavRepair {
    /**
     * Repairs [file] in place.
     *
     * @return the length of the audio data in bytes after the repair.
     * @throws IOException when the file cannot be read or written.
     */
    @Throws(IOException::class)
    fun repair(file: File, format: AudioFormat): Long = RandomAccessFile(file, "rw").use { handle ->
        val length = handle.length()
        val data = WavHeader.dataBytesFor(length, format)
        val expected = WavHeader.bytes(format, data)
        var changed = false
        if (length < WavHeader.SIZE || !headerMatches(handle, expected)) {
            handle.seek(0)
            handle.write(expected)
            changed = true
        }
        if (handle.length() != WavHeader.SIZE + data) {
            handle.setLength(WavHeader.SIZE + data)
            changed = true
        }
        if (changed) handle.fd.sync()
        data
    }

    private fun headerMatches(handle: RandomAccessFile, expected: ByteArray): Boolean {
        val actual = ByteArray(WavHeader.SIZE)
        handle.seek(0)
        handle.readFully(actual)
        return actual.contentEquals(expected)
    }
}
