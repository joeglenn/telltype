package net.jbglenn.telltype.audio.store

import java.io.File
import java.io.IOException
import net.jbglenn.telltype.audio.wav.RecordingSink
import net.jbglenn.telltype.audio.wav.RecordingSinkFactory
import net.jbglenn.telltype.core.model.audio.AudioFormat

/** [real]'s free space, or none while [override] says the disk is full. */
internal class OverriddenStorageSpace(private val real: StorageSpace, private val override: StorageOverride) :
    StorageSpace {
    override fun usableBytes(directory: File): Long? = if (override.full()) 0L else real.usableBytes(directory)
}

/** Opens [real]'s sinks; while [override] sets a limit, writes beyond it fail like a full disk. */
internal class OverriddenSinkFactory(private val real: RecordingSinkFactory, private val override: StorageOverride) :
    RecordingSinkFactory {
    override fun open(file: File, format: AudioFormat): RecordingSink {
        val sink = real.open(file, format)
        return override.bytesBeforeFull()?.let { limit -> FillingSink(sink, limit) } ?: sink
    }
}

/** A sink on a disk that fills up after [limit] bytes of audio: the write that would pass it throws `ENOSPC`. */
internal class FillingSink(private val sink: RecordingSink, private val limit: Long) : RecordingSink by sink {
    private var written = 0L

    override fun write(samples: ShortArray, count: Int) {
        val bytes = count.toLong() * Short.SIZE_BYTES
        if (written + bytes > limit) throw IOException(NO_SPACE)
        sink.write(samples, count)
        written += bytes
    }

    private companion object {
        /** How the platform words a full disk (recognised by [isNoSpace]). */
        const val NO_SPACE = "write failed: ENOSPC (No space left on device)"
    }
}
