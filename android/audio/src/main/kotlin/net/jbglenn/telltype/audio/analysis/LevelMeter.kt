package net.jbglenn.telltype.audio.analysis

import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.jbglenn.telltype.core.model.audio.AudioLevel

/**
 * The input level for the waveform: RMS amplitude of each buffer relative to full scale (32768), linear, 0 to 1,
 * as `AudioLevel` defines it. A DC offset counts towards the level (the speech check removes it; the meter shows
 * what the microphone delivers).
 *
 * The capture loop calls [update] once per buffer; it allocates nothing and publishes the level together with a
 * sequence number in a single volatile word. Collectors of [ticks] sample that word at most [MAX_RATE_HZ] times per
 * second and emit only when a new buffer arrived since the last emission, so the flow is conflated by construction
 * and a slow collector never holds up the capture loop.
 */
internal class LevelMeter {
    @Volatile
    private var packed: Long = 0L

    /** Measures the first [count] samples of [samples] and publishes the level. Called by the capture loop only. */
    fun update(samples: ShortArray, count: Int) {
        if (count <= 0) return
        var sumOfSquares = 0.0
        for (index in 0 until count) {
            val sample = samples[index].toDouble()
            sumOfSquares += sample * sample
        }
        val level = (sqrt(sumOfSquares / count) / FULL_SCALE).toFloat().coerceIn(0f, 1f)
        val sequence = (packed ushr Int.SIZE_BITS) + 1
        packed = (sequence shl Int.SIZE_BITS) or (level.toRawBits().toLong() and LOW_WORD)
    }

    /** The level of the latest buffer, or 0 before the first one. */
    fun latest(): Float = Float.fromBits((packed and LOW_WORD).toInt())

    /**
     * Levels as they change, at most [MAX_RATE_HZ] per second, until the collector cancels. Runs on the collector's
     * dispatcher; virtual time under `runTest`.
     */
    fun ticks(): Flow<AudioLevel> = flow {
        var lastSequence = 0L
        while (true) {
            val snapshot = packed
            val sequence = snapshot ushr Int.SIZE_BITS
            if (sequence != lastSequence) {
                lastSequence = sequence
                emit(AudioLevel(Float.fromBits((snapshot and LOW_WORD).toInt())))
            }
            delay(TICK)
        }
    }

    companion object {
        /** The waveform's maximum update rate (`AudioLevel`: at most 60 values per second). */
        const val MAX_RATE_HZ: Int = 60

        /** Time between two samples of the level; `delay` rounds it up to 17 ms, so the rate stays below 60/s. */
        val TICK: Duration = 1.seconds / MAX_RATE_HZ

        private const val FULL_SCALE = 32_768.0
        private const val LOW_WORD = 0xFFFF_FFFFL
    }
}
