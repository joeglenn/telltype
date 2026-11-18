package net.jbglenn.telltype.audio.wav

import java.io.File
import java.io.IOException
import net.jbglenn.telltype.core.model.audio.AudioFormat

/**
 * Where the capture loop puts samples as they arrive. Implementations write straight to the recording's file, so a
 * crash loses at most what the operating system had not yet flushed. Safe to call from two threads: the capture
 * loop writes while a stop or cancel may finalise or close from another.
 */
internal interface RecordingSink {
    /**
     * Appends the first [count] samples of [samples]. Must not allocate per call.
     *
     * @throws IOException when the write fails (full storage included) or the sink is already closed.
     */
    @Throws(IOException::class)
    fun write(samples: ShortArray, count: Int)

    /**
     * Writes the final header for everything written so far, flushes it to storage and closes. Idempotent: a second
     * call returns the same length without touching the file.
     *
     * @return the length of the audio data in bytes.
     * @throws IOException when the header or the flush fails; the sink is closed anyway.
     */
    @Throws(IOException::class)
    fun finalise(): Long

    /**
     * Closes without fixing the header, for a recording that is about to be deleted. Idempotent, never throws.
     *
     * @return the failure when the file could not be closed cleanly (for the caller to log), otherwise null.
     */
    fun close(): IOException?
}

/** Opens a [RecordingSink] on a recording file. */
internal fun interface RecordingSinkFactory {
    /**
     * Opens [file] for appending [format] samples, keeping any audio it already holds.
     *
     * @throws IOException when the file cannot be opened or prepared.
     */
    @Throws(IOException::class)
    fun open(file: File, format: AudioFormat): RecordingSink
}
