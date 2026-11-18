package net.jbglenn.telltype.audio

import android.Manifest
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.jbglenn.telltype.audio.capture.AudioRecordSourceFactory
import net.jbglenn.telltype.audio.capture.AudioRecorder
import net.jbglenn.telltype.audio.capture.CaptureDevices
import net.jbglenn.telltype.audio.capture.CaptureFiles
import net.jbglenn.telltype.audio.platform.AndroidAudioFocus
import net.jbglenn.telltype.audio.platform.AndroidInterruptionMonitor
import net.jbglenn.telltype.audio.platform.AndroidMicrophonePermission
import net.jbglenn.telltype.audio.store.FileRecordingStore
import net.jbglenn.telltype.audio.store.RecordingFiles
import net.jbglenn.telltype.audio.store.SystemStorageSpace
import net.jbglenn.telltype.audio.wav.WavFileWriter
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.testing.FakeSettingsRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real recorder on a device: `AudioRecord`, the WAV writer, the store in `noBackupFilesDir`, Android focus and
 * the interruption monitor. The emulators on the build machine have no microphone hardware, so the input is silence.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecorderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioThread = Executors.newSingleThreadExecutor { Thread(it, "test-audio") }.asCoroutineDispatcher()
    private val dispatchers = DeviceDispatchers(audioThread)
    private val logger = LogcatLogger()
    private val files = RecordingFiles(File(context.noBackupFilesDir, RecordingFiles.DIRECTORY_NAME))
    private val store = FileRecordingStore(files, UuidIds(), SystemStorageSpace(context), dispatchers, logger)
    private val recorder = AudioRecorder(
        files = CaptureFiles(files, WavFileWriter.Factory(), dispatchers, logger),
        devices = CaptureDevices(
            sources = AudioRecordSourceFactory(),
            permission = AndroidMicrophonePermission(context),
            focus = AndroidAudioFocus(context),
            monitor = AndroidInterruptionMonitor(context),
        ),
        settings = FakeSettingsRepository(),
        dispatchers = dispatchers,
        logger = logger,
    )

    @Before
    fun grantMicrophoneAndClearRecordings() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        recordingFiles().forEach { it.delete() }
    }

    @After
    fun closeAudioThread() {
        audioThread.close()
    }

    @Test
    fun twoSecondsFromTheMicrophoneMakeAValid16kMonoWav() = runBlocking {
        val recording = store.create(SessionId("device-test")).orFail()
        val file = checkNotNull(files.find(recording)).file
        val source = AudioRecordSourceFactory().open(AudioFormat.Dictation) {}.orFail()
        val writer = WavFileWriter.openWriter(file, AudioFormat.Dictation)
        val buffer = ShortArray(320)
        var written = 0
        var zeros = 0L
        var peak = 0
        try {
            while (written < TWO_SECONDS) {
                val count = source.read(buffer)
                assertTrue("read failed with $count", count >= 0)
                val take = minOf(count, TWO_SECONDS - written)
                writer.write(buffer, take)
                for (index in 0 until take) {
                    val sample = buffer[index].toInt()
                    if (sample == 0) zeros++
                    peak = maxOf(peak, kotlin.math.abs(sample))
                }
                written += take
            }
        } finally {
            source.close()
        }
        writer.finalise()
        Log.i(LOG_TAG, "emulator microphone: $written samples, $zeros exactly zero, peak |sample| $peak")

        val clip = store.finalise(recording).orFail()
        assertEquals(2.seconds, clip.duration)
        assertEquals(file.absolutePath, clip.path)
        assertValidDictationWav(file, dataBytes = TWO_SECONDS * 2L)
        assertEquals(Outcome.Ok(Unit), store.delete(recording))
        assertTrue(recordingFiles().isEmpty())
    }

    @Test
    fun theRecorderClassifiesTheEmulatorsSilenceAndKeepsNoFile() = runBlocking {
        val recording = store.create(SessionId("device-test")).orFail()
        val begun = TimeSource.Monotonic.markNow()

        recorder.start(recording).orFail()
        val startLatency = begun.elapsedNow()
        delay(2.seconds)
        val result = recorder.stop()

        Log.i(LOG_TAG, "start took $startLatency; two seconds of emulator input stopped with $result")
        assertTrue("start within its timeout: $startLatency", startLatency < AudioRecorder.START_TIMEOUT)
        assertTrue(
            "silence is no speech or microphone in use, got $result",
            result == Outcome.Err(NoSpeech) || result == Outcome.Err(MicrophoneInUse),
        )
        assertTrue("no file kept", recordingFiles().isEmpty())
    }

    @Test
    fun cancelLeavesNoFileAndTheMicrophoneCanBeOpenedAgain() = runBlocking {
        recorder.start(store.create(SessionId("device-test")).orFail()).orFail()
        delay(0.5.seconds)

        recorder.cancel()

        assertTrue(recordingFiles().isEmpty())
        assertEquals(emptyList<RecordingId>(), store.findOrphans(emptySet()).orFail())
        val again = store.create(SessionId("device-test")).orFail()
        recorder.start(again).orFail()
        recorder.cancel()
        assertTrue(recordingFiles().isEmpty())
    }

    private fun recordingFiles(): List<File> = files.directory.listFiles()?.toList().orEmpty()

    private fun assertValidDictationWav(file: File, dataBytes: Long) {
        val header = ByteBuffer.wrap(file.readBytes(), 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        fun text() = String(ByteArray(4).also { header.get(it) }, Charsets.US_ASCII)
        assertEquals("RIFF", text())
        assertEquals(36 + dataBytes, header.int.toLong())
        assertEquals("WAVE", text())
        assertEquals("fmt ", text())
        assertEquals(16, header.int)
        assertEquals("PCM", 1, header.short.toInt())
        assertEquals("mono", 1, header.short.toInt())
        assertEquals("16 kHz", 16_000, header.int)
        assertEquals(32_000, header.int)
        assertEquals(2, header.short.toInt())
        assertEquals("16-bit", 16, header.short.toInt())
        assertEquals("data", text())
        assertEquals(dataBytes, header.int.toLong())
        assertEquals(44 + dataBytes, file.length())
    }

    private fun <T, E> Outcome<T, E>.orFail(): T = when (this) {
        is Outcome.Ok -> value

        is Outcome.Err -> {
            fail("expected success, got $error")
            error("unreachable")
        }
    }

    private class DeviceDispatchers(override val audio: ExecutorCoroutineDispatcher) : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val accessibility: CoroutineDispatcher = Dispatchers.Default
    }

    private class UuidIds : IdGenerator {
        override fun newSessionId() = SessionId(UUID.randomUUID().toString())

        override fun newRecordingId() = RecordingId(UUID.randomUUID().toString())
    }

    private class LogcatLogger : Logger {
        override fun isEnabled(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String) {
            Log.println(Log.INFO, tag.name, message() + (throwable?.let { " ($it)" } ?: ""))
        }
    }

    private companion object {
        const val TWO_SECONDS = 32_000
        const val LOG_TAG = "AudioDeviceTest"
    }
}
