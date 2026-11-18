package net.jbglenn.telltype.audio.analysis

import kotlin.math.pow

/** What the energy check found in a recording. */
internal enum class SpeechVerdict {
    /** Nothing above the quiet-room floor: "Didn't catch that", nothing is uploaded. */
    NONE,

    /** Some sound, but too little at speech level: uploaded, but any text is dropped (`SpeechPresence.FAINT`). */
    FAINT,

    /** Enough speech-level sound to be worth transcribing (`SpeechPresence.CLEAR`). */
    CLEAR,
}

/**
 * A deliberately simple energy-based speech check (docs/PLAN.md section 8.1). The audio is cut into 20 ms frames;
 * each frame's energy is its mean square after removing the frame's mean (so a DC offset is not mistaken for
 * sound). A frame at or above [SPEECH_FRAME_DBFS] counts as speech, one at or above [SIGNAL_FRAME_DBFS] as sound.
 *
 * - [SpeechVerdict.CLEAR]: at least [MIN_SPEECH_FRAMES] speech frames (150 ms) anywhere in the clip.
 * - [SpeechVerdict.FAINT]: not clear, but at least [MIN_SIGNAL_FRAMES] sound frames (150 ms).
 * - [SpeechVerdict.NONE]: otherwise.
 *
 * Levels are dBFS relative to a full-scale square wave (amplitude 32768; a full-scale sine is -3 dBFS). The
 * thresholds are initial values, to be tuned on the phone (work package P1). Their basis is the Android CDD
 * (section 5.4.1): with the voice-recognition source, a 90 dB SPL tone at 1 kHz should read about RMS 2500, that is
 * -22 dBFS, so dBFS is roughly dB SPL minus 112:
 * - [SPEECH_FRAME_DBFS] = -50 dBFS is about 62 dB SPL at the microphone. Normal speech at dictation distance
 *   (10 to 30 cm) is 65 to 80 dB SPL in its voiced frames; a quiet room (30 to 45 dB SPL) is 15 dB or more below.
 * - [SIGNAL_FRAME_DBFS] = -65 dBFS is about 47 dB SPL: above a quiet room, at the level of a whisper or of speech
 *   across the room. Clips that never reach it are treated as silence and never uploaded.
 * - 150 ms of frames: shorter than the voiced part of the shortest one-word answer ("yes", "no"), longer than a
 *   click or a tap on the phone.
 *
 * The check errs towards uploading: losing a real dictation is worse than a wasted upload. Constant memory: three
 * counters and one partial frame, whatever the clip length. Not thread-safe; fed by one capture loop.
 */
internal class SpeechDetector {
    private var frameFill = 0
    private var frameSum = 0L
    private var frameSumOfSquares = 0L
    private var speechFrames = 0L
    private var signalFrames = 0L

    /** Feeds the first [count] samples of [samples]. Frames may span calls. */
    fun accept(samples: ShortArray, count: Int) {
        for (index in 0 until count) {
            val sample = samples[index].toLong()
            frameSum += sample
            frameSumOfSquares += sample * sample
            if (++frameFill == FRAME_SAMPLES) closeFrame()
        }
    }

    /** The verdict for everything fed so far; a trailing partial frame is ignored. */
    fun verdict(): SpeechVerdict = when {
        speechFrames >= MIN_SPEECH_FRAMES -> SpeechVerdict.CLEAR
        signalFrames >= MIN_SIGNAL_FRAMES -> SpeechVerdict.FAINT
        else -> SpeechVerdict.NONE
    }

    private fun closeFrame() {
        val mean = frameSum.toDouble() / FRAME_SAMPLES
        val energy = frameSumOfSquares.toDouble() / FRAME_SAMPLES - mean * mean
        if (energy >= SPEECH_ENERGY) speechFrames++
        if (energy >= SIGNAL_ENERGY) signalFrames++
        frameFill = 0
        frameSum = 0
        frameSumOfSquares = 0
    }

    companion object {
        /** Samples per analysis frame: 20 ms at 16 kHz. */
        const val FRAME_SAMPLES: Int = 320

        /** A frame at or above this level (dBFS, mean removed) counts as speech. */
        const val SPEECH_FRAME_DBFS: Double = -50.0

        /** A frame at or above this level (dBFS, mean removed) counts as sound rather than silence. */
        const val SIGNAL_FRAME_DBFS: Double = -65.0

        /** Speech frames needed for [SpeechVerdict.CLEAR]: 150 ms. */
        const val MIN_SPEECH_FRAMES: Int = 8

        /** Sound frames needed for [SpeechVerdict.FAINT]: 150 ms. */
        const val MIN_SIGNAL_FRAMES: Int = 8

        private const val FULL_SCALE = 32_768.0
        private const val DECIBELS_PER_DECADE = 20.0
        private const val BASE = 10.0

        private val SPEECH_ENERGY = energyOf(SPEECH_FRAME_DBFS)
        private val SIGNAL_ENERGY = energyOf(SIGNAL_FRAME_DBFS)

        /** Mean square, in sample units, of a signal whose RMS is [dbfs]. */
        fun energyOf(dbfs: Double): Double {
            val rms = FULL_SCALE * BASE.pow(dbfs / DECIBELS_PER_DECADE)
            return rms * rms
        }
    }
}
