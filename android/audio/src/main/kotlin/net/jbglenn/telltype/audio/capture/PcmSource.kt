package net.jbglenn.telltype.audio.capture

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.error.CaptureError

/**
 * A running microphone delivering 16-bit PCM: `AudioRecord` in production, a scripted signal in tests. Used by one
 * capture loop on the audio dispatcher.
 */
public interface PcmSource {
    /**
     * Reads up to `buffer.size` samples into [buffer], from index 0. The real source blocks for about one buffer of
     * audio (that is why it only runs on the audio dispatcher); a scripted source suspends instead.
     *
     * @return the number of samples read (0 when nothing was ready), or a negative `AudioRecord` error code when the
     *   input failed and will not recover.
     */
    public suspend fun read(buffer: ShortArray): Int

    /** Stops and releases the microphone. Idempotent; never throws. */
    public fun close()
}

/** Opens the microphone. */
public interface PcmSourceFactory {
    /**
     * Opens and starts a source for [format]. [onSilenced] is called, from any thread, when the system silences this
     * client because another app or a call took the microphone.
     *
     * @return the running source, or `MicrophonePermissionMissing`, `MicrophoneInUse` or `RecorderFailed`.
     */
    public suspend fun open(format: AudioFormat, onSilenced: () -> Unit): Outcome<PcmSource, CaptureError>
}

/**
 * Replaces the microphone in debug builds (docs/PLAN.md Appendix G, "Use fake engines"): `:app`'s debug source set
 * binds one to play a bundled speech clip, because the emulator's microphone delivers only noise. Release builds
 * bind none, and [PcmSourceFactory] is then the real `AudioRecord` factory.
 */
public fun interface MicrophoneOverride {
    /** The factory to use instead of [real]; it may delegate to [real] whenever the override is switched off. */
    public fun wrap(real: PcmSourceFactory): PcmSourceFactory
}
