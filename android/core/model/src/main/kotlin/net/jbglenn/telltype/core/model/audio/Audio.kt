package net.jbglenn.telltype.core.model.audio

import kotlin.time.Duration
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId

/** The file format a recording is stored and uploaded in (open question P2 in docs/PLAN.md Appendix A). */
public enum class AudioEncoding {
    /** RIFF WAV with linear PCM samples. The starting format. */
    WAV,

    /** Opus in an Ogg container, if the first speed run shows upload time matters. */
    OGG_OPUS,
}

/**
 * The sample format of a recording.
 *
 * @property sampleRateHz samples per second per channel.
 * @property channelCount number of channels; 1 for dictation.
 * @property bitsPerSample bits per PCM sample as captured (before any compression).
 * @property encoding how the samples are stored in the file.
 */
public data class AudioFormat(
    public val sampleRateHz: Int,
    public val channelCount: Int,
    public val bitsPerSample: Int,
    public val encoding: AudioEncoding,
) {
    public companion object {
        /** What the recorder captures (docs/PLAN.md section 7.3): 16 kHz, mono, 16-bit, stored as WAV. */
        public val Dictation: AudioFormat =
            AudioFormat(sampleRateHz = 16_000, channelCount = 1, bitsPerSample = 16, encoding = AudioEncoding.WAV)
    }
}

/** What the silence check found in a finished recording (docs/PLAN.md sections 8.1 and 8.2). */
public enum class SpeechPresence {
    /** Speech was detected. */
    CLEAR,

    /**
     * Near-silent: some signal, but under the speech threshold for almost all of the clip. It is still uploaded,
     * but any text an engine produces from it is dropped as "Didn't catch that" (speech models invent text from
     * silence). A clip with no signal at all never becomes an [AudioClip]: capture reports `NoSpeech` instead.
     */
    FAINT,
}

/**
 * A finished recording on disk, ready to transcribe. The file stays until the session succeeds or the error
 * policy deletes it (docs/PLAN.md section 10).
 *
 * @property recording the recording id.
 * @property session the session it was recorded for (sent as `X-Request-Id`, also for queued jobs).
 * @property path absolute path of the file in app-private storage.
 * @property format the file's format.
 * @property duration length of the audio.
 * @property speech the silence check's verdict.
 * @property incognito whether the recording was made for an incognito field (`RecordingStore.create` keeps the mark
 *   with the file), so a recording recovered after a crash is never saved to History. Added by A7h.
 */
public data class AudioClip(
    public val recording: RecordingId,
    public val session: SessionId,
    public val path: String,
    public val format: AudioFormat,
    public val duration: Duration,
    public val speech: SpeechPresence,
    public val incognito: Boolean = false,
)

/**
 * The input level of the most recent audio frame, for the waveform: RMS amplitude relative to full scale,
 * linear, from 0.0 (silence) to 1.0 (full scale). Emitted at most 60 times per second.
 */
@JvmInline
public value class AudioLevel(public val value: Float) {
    init {
        require(value in 0f..1f) { "an audio level is between 0 and 1, got $value" }
    }
}

/**
 * Something that ends Listening early. The session moves straight to Working with what was captured
 * (docs/PLAN.md section 6.4). Reported by `AudioCapture.interruptions` while a recording runs.
 */
public enum class Interruption {
    /** The screen turned off. */
    SCREEN_OFF,

    /** A phone call started (ringing or off-hook). */
    CALL_STARTED,

    /**
     * The microphone stopped delivering audio: another app or the system took it, or the input became all
     * zeros. If nothing was captured before, stopping reports `MicrophoneInUse`.
     */
    MICROPHONE_LOST,

    /**
     * Another app took audio focus (an alarm, a navigation prompt, a video started) while "Pause other audio while
     * recording" was on. Only reported when the recording holds focus; a short duck request does not count.
     */
    AUDIO_FOCUS_LOST,

    /**
     * Writing the recording failed (storage full or another file error), so capture stopped. Stopping returns the
     * audio captured up to that point when it holds speech, otherwise the storage error.
     */
    RECORDING_FAILED,
}
