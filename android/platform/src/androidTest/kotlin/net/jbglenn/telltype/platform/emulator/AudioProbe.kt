package net.jbglenn.telltype.platform.emulator

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/** Records two seconds of 16 kHz mono from the microphone in this process and reports what the system allowed. */
internal object AudioProbe {
    /** What an `AudioRecord` in this process did. */
    data class Result(
        val initialized: Boolean,
        val recordingState: Int,
        val silenced: Boolean?,
        val framesRead: Int,
        val peak: Int,
        val error: String?,
    )

    /** Runs the probe. */
    fun run(): Result {
        val created = runCatching { newRecord() }
        val record = created.getOrNull()
        return when {
            record == null -> Result(false, -1, null, 0, 0, created.exceptionOrNull()?.javaClass?.simpleName)
            record.state != AudioRecord.STATE_INITIALIZED -> Result(false, -1, null, 0, 0, "not initialized")
            else -> record(record)
        }.also { record?.release() }
    }

    private fun newRecord(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum, RATE))
            .build()
    }

    /** Records half a second [READS] times and reports what happened; stops [record], the caller releases it. */
    private fun record(record: AudioRecord): Result = try {
        record.startRecording()
        val buffer = ShortArray(RATE / 2)
        var frames = 0
        var peak = 0
        repeat(READS) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                frames += read
                for (i in 0 until read) peak = maxOf(peak, abs(buffer[i].toInt()))
            }
        }
        val silenced = record.activeRecordingConfiguration?.isClientSilenced
        Result(true, record.recordingState, silenced, frames, peak, null)
    } catch (e: IllegalStateException) {
        Result(true, record.recordingState, null, 0, 0, e.javaClass.simpleName)
    } finally {
        runCatching { record.stop() }
    }

    private const val READS = 4
    private const val RATE = 16_000
}
