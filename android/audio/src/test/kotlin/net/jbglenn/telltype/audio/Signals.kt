package net.jbglenn.telltype.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A deterministic test signal: sample number to 16-bit sample. */
internal fun interface Signal {
    /** The sample at [index] (0 = first sample of the recording). */
    fun sample(index: Long): Short
}

/** Synthetic signals at 16 kHz, levels in dBFS relative to full scale 32768 (as the speech check measures). */
internal object Signals {
    private const val RATE = 16_000.0
    private const val FULL_SCALE = 32_768.0
    private const val MAX_SAMPLE = 32_767
    private const val TONE_HZ = 440.0

    /** Digital silence: what Android delivers while another app holds the microphone. */
    val zeros: Signal = Signal { 0 }

    /** A sine of RMS [dbfs] at [hz]. */
    fun tone(dbfs: Double, hz: Double = TONE_HZ): Signal {
        val amplitude = rms(dbfs) * sqrt(2.0)
        return Signal { index -> clamp(amplitude * sin(2 * PI * hz * index / RATE)) }
    }

    /** A full-scale sine (amplitude 32767). */
    fun fullScaleSine(hz: Double = TONE_HZ): Signal =
        Signal { index -> clamp(MAX_SAMPLE * sin(2 * PI * hz * index / RATE)) }

    /** A constant value. */
    fun dc(value: Short): Signal = Signal { value }

    /** White noise of RMS [dbfs] (uniform, from a hash of the index, so any sample can be computed alone). */
    fun noise(dbfs: Double, seed: Long = 1): Signal {
        val amplitude = rms(dbfs) * sqrt(3.0)
        return Signal { index -> clamp(amplitude * (2 * unit(index, seed) - 1)) }
    }

    /** [on] during the first [onMillis] of every [periodMillis], [off] otherwise. */
    fun bursts(on: Signal, onMillis: Int, periodMillis: Int, off: Signal = zeros): Signal = Signal { index ->
        val millis = index * 1000 / RATE.toLong()
        if (millis % periodMillis < onMillis) on.sample(index) else off.sample(index)
    }

    /** [first] for the first [millis], then [then]. */
    fun after(millis: Int, first: Signal, then: Signal): Signal {
        val switchAt = millis * RATE.toLong() / 1000
        return Signal { index -> if (index < switchAt) first.sample(index) else then.sample(index) }
    }

    /** The sum of two signals, clamped. */
    fun mix(a: Signal, b: Signal): Signal = Signal { index -> clamp(a.sample(index).toDouble() + b.sample(index)) }

    /** RMS in sample units of a level in dBFS. */
    fun rms(dbfs: Double): Double = FULL_SCALE * 10.0.pow(dbfs / 20)

    /** [count] samples of [signal] from [start]. */
    fun samples(signal: Signal, count: Int, start: Long = 0): ShortArray =
        ShortArray(count) { signal.sample(start + it) }

    private fun clamp(value: Double): Short = value.roundToInt().coerceIn(-MAX_SAMPLE - 1, MAX_SAMPLE).toShort()

    /** A uniform value in [0, 1) from SplitMix64 of [index] and [seed]. */
    private fun unit(index: Long, seed: Long): Double {
        var z = index * -0x61c8864680b583ebL + seed
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
