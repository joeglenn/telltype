package net.jbglenn.telltype.audio.capture

import android.media.AudioFormat as PlatformFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import java.util.concurrent.Executor
import javax.inject.Inject
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.flatMap
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.MicrophoneInUse
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.RecorderFailed

/**
 * Opens `AudioRecord` with the voice-recognition source (docs/PLAN.md section 7.3: no automatic gain or noise
 * suppression, tuned for speech recognisers), mono 16-bit PCM. The internal buffer holds [INTERNAL_BUFFER_SECONDS]
 * of audio so a slow disk write on the capture thread never overruns it.
 */
internal class AudioRecordSourceFactory @Inject constructor() : PcmSourceFactory {
    override suspend fun open(format: AudioFormat, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError> =
        build(format).flatMap { record -> start(record, onSilenced) }

    private fun build(format: AudioFormat): Outcome<AudioRecord, CaptureError> {
        val minimum = AudioRecord.getMinBufferSize(format.sampleRateHz, CHANNEL_MASK, ENCODING)
        val bytesPerSecond = format.sampleRateHz * format.channelCount * format.bitsPerSample / BITS_PER_BYTE
        val bufferBytes = maxOf(minimum, (bytesPerSecond * INTERNAL_BUFFER_SECONDS).toInt())
        return try {
            if (minimum <= 0) {
                Outcome.Err(RecorderFailed)
            } else {
                Outcome.Ok(
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                        .setAudioFormat(
                            PlatformFormat.Builder()
                                .setSampleRate(format.sampleRateHz)
                                .setChannelMask(CHANNEL_MASK)
                                .setEncoding(ENCODING)
                                .build(),
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .build(),
                )
            }
        } catch (_: SecurityException) {
            Outcome.Err(MicrophonePermissionMissing)
        } catch (_: UnsupportedOperationException) {
            Outcome.Err(RecorderFailed)
        }
    }

    /** Starts [record]; on any failure it is released before returning. */
    private fun start(record: AudioRecord, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError> {
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Outcome.Err(RecorderFailed)
        }
        val callback = SilenceCallback(record, onSilenced)
        record.registerAudioRecordingCallback(DirectExecutor, callback)
        val failure = startRecording(record)
        return if (failure == null) {
            Outcome.Ok(AudioRecordSource(record, callback))
        } else {
            record.unregisterAudioRecordingCallback(callback)
            record.release()
            Outcome.Err(failure)
        }
    }

    /** Null once recording runs; `MicrophoneInUse` when the system would not start it (another app holds it). */
    private fun startRecording(record: AudioRecord): CaptureError? = try {
        record.startRecording()
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) null else MicrophoneInUse
    } catch (_: IllegalStateException) {
        RecorderFailed
    }

    /** Reports when the system silences this client (Android 10+ concurrent capture rules). */
    private class SilenceCallback(private val record: AudioRecord, private val onSilenced: () -> Unit) :
        AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (record.activeRecordingConfiguration?.isClientSilenced == true) onSilenced()
        }
    }

    /** Runs the recording callback on the thread that delivers it; the callback only sets flags. */
    private object DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    /** The running recorder. */
    private class AudioRecordSource(
        private val record: AudioRecord,
        private val callback: AudioManager.AudioRecordingCallback,
    ) : PcmSource {
        private var closed = false

        override suspend fun read(buffer: ShortArray): Int =
            record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            record.unregisterAudioRecordingCallback(callback)
            // stop() throws only for a recorder that never initialised, and such a recorder never becomes a source.
            try {
                record.stop()
            } finally {
                record.release()
            }
        }
    }

    private companion object {
        const val CHANNEL_MASK = PlatformFormat.CHANNEL_IN_MONO
        const val ENCODING = PlatformFormat.ENCODING_PCM_16BIT
        const val BITS_PER_BYTE = 8
        const val INTERNAL_BUFFER_SECONDS = 0.5
    }
}
