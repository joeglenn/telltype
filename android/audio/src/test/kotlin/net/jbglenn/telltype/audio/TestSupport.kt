package net.jbglenn.telltype.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import net.jbglenn.telltype.audio.capture.AudioRecorder
import net.jbglenn.telltype.audio.capture.CaptureDevices
import net.jbglenn.telltype.audio.capture.CaptureFiles
import net.jbglenn.telltype.audio.store.FileRecordingStore
import net.jbglenn.telltype.audio.store.RecordingFiles
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.SequentialIdGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/** Five dispatchers on one virtual-time scheduler, but distinct, so a test can see which one ran a piece of work. */
internal class SplitTestDispatchers(scheduler: TestCoroutineScheduler) : DispatcherProvider {
    override val main: CoroutineDispatcher = StandardTestDispatcher(scheduler, "main")
    override val io: CoroutineDispatcher = StandardTestDispatcher(scheduler, "io")
    override val default: CoroutineDispatcher = StandardTestDispatcher(scheduler, "default")
    override val accessibility: CoroutineDispatcher = StandardTestDispatcher(scheduler, "accessibility")
    override val audio: CoroutineDispatcher = StandardTestDispatcher(scheduler, "audio")
}

/** A recorder and a store over a temporary directory, with every platform part faked. */
internal class RecorderHarness(
    scheduler: TestCoroutineScheduler,
    val directory: File,
    signal: Signal,
    settings: Settings = Settings.Defaults,
) {
    val dispatchers = SplitTestDispatchers(scheduler)
    val files = RecordingFiles(directory)
    val logger = RecordingLogger()
    val store = FileRecordingStore(files, SequentialIdGenerator(), { Long.MAX_VALUE }, dispatchers, logger)
    val sources = ScriptedSources(signal)
    val sinks = FaultySinks()
    val focus = FakeAudioFocus()
    val monitor = FakeInterruptionMonitor()
    val permission = FakeMicrophonePermission()
    val settings = FakeSettingsRepository(settings)
    val recorder = AudioRecorder(
        files = CaptureFiles(files, sinks, dispatchers, logger),
        devices = CaptureDevices(sources, permission, focus, monitor),
        settings = this.settings,
        dispatchers = dispatchers,
        logger = logger,
    )

    /** A new recording file for session-1. */
    suspend fun newRecording(): RecordingId = store.create(SessionId("session-1")).orFail()

    /** The recording files on disk. */
    fun filesOnDisk(): List<File> = directory.listFiles()?.filter { it.isFile }.orEmpty()
}

/** The success value, or a test failure naming the error. */
internal fun <T, E> Outcome<T, E>.orFail(): T = when (this) {
    is Outcome.Ok -> value

    is Outcome.Err -> {
        fail("expected success, got $error")
        error("unreachable")
    }
}

/** The fields of a canonical 44-byte WAV header, read back from a file. */
internal data class ParsedWav(
    val riff: String,
    val riffSize: Long,
    val wave: String,
    val fmt: String,
    val fmtSize: Int,
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val byteRate: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val data: String,
    val dataSize: Long,
    val fileLength: Long,
) {
    /** Asserts a valid 16 kHz mono 16-bit PCM header describing exactly the bytes after it. */
    fun assertValidDictation(expectedDataBytes: Long) {
        assertEquals("RIFF", riff)
        assertEquals("WAVE", wave)
        assertEquals("fmt ", fmt)
        assertEquals(16, fmtSize)
        assertEquals(1, formatTag)
        assertEquals(1, channels)
        assertEquals(16_000, sampleRate)
        assertEquals(32_000, byteRate)
        assertEquals(2, blockAlign)
        assertEquals(16, bitsPerSample)
        assertEquals("data", data)
        assertEquals(expectedDataBytes, dataSize)
        assertEquals(36 + expectedDataBytes, riffSize)
        assertEquals(44 + expectedDataBytes, fileLength)
    }

    companion object {
        /** Reads the header of [file]. */
        fun of(file: File): ParsedWav {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
            fun text(): String = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
            return ParsedWav(
                riff = text(),
                riffSize = buffer.int.toLong() and 0xFFFF_FFFFL,
                wave = text(),
                fmt = text(),
                fmtSize = buffer.int,
                formatTag = buffer.short.toInt(),
                channels = buffer.short.toInt(),
                sampleRate = buffer.int,
                byteRate = buffer.int,
                blockAlign = buffer.short.toInt(),
                bitsPerSample = buffer.short.toInt(),
                data = text(),
                dataSize = buffer.int.toLong() and 0xFFFF_FFFFL,
                fileLength = bytes.size.toLong(),
            )
        }

        /** The samples after the header of [file]. */
        fun samplesOf(file: File): ShortArray {
            val bytes = file.readBytes()
            val buffer = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return ShortArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}
