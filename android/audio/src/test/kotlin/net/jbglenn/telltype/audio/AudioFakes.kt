package net.jbglenn.telltype.audio

import java.io.File
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import net.jbglenn.telltype.audio.capture.PcmSource
import net.jbglenn.telltype.audio.capture.PcmSourceFactory
import net.jbglenn.telltype.audio.platform.AudioFocus
import net.jbglenn.telltype.audio.platform.InterruptionMonitor
import net.jbglenn.telltype.audio.platform.MicrophonePermission
import net.jbglenn.telltype.audio.platform.Registration
import net.jbglenn.telltype.audio.wav.RecordingSink
import net.jbglenn.telltype.audio.wav.RecordingSinkFactory
import net.jbglenn.telltype.audio.wav.WavFileWriter
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.Interruption
import net.jbglenn.telltype.core.model.error.CaptureError

/**
 * A microphone that plays [signal] in real time on virtual time: each read suspends for the duration of the samples
 * it returns, as a blocking `AudioRecord.read` would.
 */
internal class ScriptedSources(var signal: Signal) : PcmSourceFactory {
    /** When set, opening fails with this error. */
    var openError: CaptureError? = null

    /** How long opening takes; [Duration.INFINITE] never returns. */
    var openDelay: Duration = Duration.ZERO

    /** Samples per read; null fills the whole buffer. */
    var samplesPerRead: Int? = null

    /** Reads never return (no audio ever flows). */
    var stallReads: Boolean = false

    /** Reads from this sample on fail with `ERROR_DEAD_OBJECT`. */
    var failAtSample: Long? = null

    /** Every source opened, oldest first. */
    val opened: MutableList<Source> = mutableListOf()

    /** The dispatcher of every read. */
    val readDispatchers: MutableSet<ContinuationInterceptor?> = mutableSetOf()

    /** Every distinct buffer array the recorder read into (compared by identity). */
    val buffers: MutableList<ShortArray> = mutableListOf()

    override suspend fun open(format: AudioFormat, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError> {
        if (openDelay == Duration.INFINITE) awaitCancellation()
        delay(openDelay)
        openError?.let { return Outcome.Err(it) }
        return Outcome.Ok(Source(onSilenced).also(opened::add))
    }

    /** One opened microphone. */
    inner class Source(private val onSilenced: () -> Unit) : PcmSource {
        /** Samples delivered so far. */
        var position: Long = 0
            private set

        /** Whether the recorder closed it. */
        var closed: Boolean = false
            private set

        /** Reads after [close]: must stay 0. */
        var readsAfterClose: Int = 0
            private set

        /** Simulates the system silencing this client (another app or a call took the microphone). */
        fun silence() = onSilenced()

        override suspend fun read(buffer: ShortArray): Int {
            if (closed) readsAfterClose++
            readDispatchers += currentCoroutineContext()[ContinuationInterceptor]
            if (buffers.none { it === buffer }) buffers += buffer
            if (stallReads) awaitCancellation()
            val failAt = failAtSample
            if (failAt != null && position >= failAt) return ERROR_DEAD_OBJECT
            val count = minOf(samplesPerRead ?: buffer.size, buffer.size)
            delay(count * MILLIS_PER_SECOND / SAMPLE_RATE)
            for (index in 0 until count) buffer[index] = signal.sample(position + index)
            position += count
            return count
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val ERROR_DEAD_OBJECT = -6
        const val MILLIS_PER_SECOND = 1_000L
        const val SAMPLE_RATE = 16_000L
    }
}

/** Real WAV writers that can be told to fail like a full or broken disk. */
internal class FaultySinks : RecordingSinkFactory {
    /** After this many data bytes, writes fail with [failure]. */
    var failAfterBytes: Long? = null

    /** The failure [failAfterBytes] raises. */
    var failure: IOException = IOException("write failed: ENOSPC (No space left on device)")

    override fun open(file: File, format: AudioFormat): RecordingSink {
        val real = WavFileWriter.openWriter(file, format)
        return object : RecordingSink by real {
            private var written = 0L

            override fun write(samples: ShortArray, count: Int) {
                val limit = failAfterBytes
                if (limit != null && written + count * 2 > limit) throw failure
                real.write(samples, count)
                written += count * 2
            }
        }
    }
}

/** Audio focus that records requests and releases and can be taken away. */
internal class FakeAudioFocus : AudioFocus {
    /** Whether the system grants focus. */
    var grants: Boolean = true

    /** Requests so far. */
    var requests: Int = 0
        private set

    /** Releases so far. */
    var releases: Int = 0
        private set

    private var onLost: (() -> Unit)? = null

    /** Whether focus is held now. */
    val held: Boolean get() = onLost != null

    override fun request(onLost: () -> Unit): Registration? {
        requests++
        if (!grants) return null
        this.onLost = onLost
        return Registration {
            if (this.onLost != null) {
                this.onLost = null
                releases++
            }
        }
    }

    /** Another app takes focus. */
    fun lose() {
        onLost?.invoke()
    }
}

/** An interruption monitor driven by the test. */
internal class FakeInterruptionMonitor : InterruptionMonitor {
    private var listener: ((Interruption) -> Unit)? = null

    /** Watches started so far. */
    var watches: Int = 0
        private set

    /** Whether someone is watching now. */
    val watching: Boolean get() = listener != null

    override fun watch(onInterruption: (Interruption) -> Unit): Registration {
        watches++
        listener = onInterruption
        return Registration { listener = null }
    }

    /** The system reports [cause]. */
    fun fire(cause: Interruption) {
        listener?.invoke(cause)
    }
}

/** A switchable permission. */
internal class FakeMicrophonePermission(var granted: Boolean = true) : MicrophonePermission {
    override fun isGranted(): Boolean = granted
}
