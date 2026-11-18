package net.jbglenn.telltype.audio.store

import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.audio.analysis.SpeechVerdict
import net.jbglenn.telltype.audio.wav.WavHeader
import net.jbglenn.telltype.core.model.audio.AudioClip
import net.jbglenn.telltype.core.model.audio.AudioFormat
import net.jbglenn.telltype.core.model.audio.SpeechPresence

/** The length of [dataBytes] bytes of [format] audio, exact to the nanosecond. */
internal fun durationOf(dataBytes: Long, format: AudioFormat): Duration {
    val frames = dataBytes / WavHeader.blockAlign(format)
    return (frames * 1.seconds.inWholeNanoseconds / format.sampleRateHz).nanoseconds
}

/** [this] recording as a finished clip of [dataBytes] bytes of [format] audio. */
internal fun StoredRecording.toClip(dataBytes: Long, format: AudioFormat, speech: SpeechPresence): AudioClip =
    AudioClip(
        recording = recording,
        session = session,
        path = file.absolutePath,
        format = format,
        duration = durationOf(dataBytes, format),
        speech = speech,
        incognito = incognito,
    )

/** The clip verdict for a verdict that is not [SpeechVerdict.NONE]; NONE maps to FAINT (text will be dropped). */
internal fun SpeechVerdict.toPresence(): SpeechPresence =
    if (this == SpeechVerdict.CLEAR) SpeechPresence.CLEAR else SpeechPresence.FAINT
