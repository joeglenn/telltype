package net.jbglenn.telltype.audio.analysis

/**
 * Watches for digital silence, which a real microphone never produces: Android hands a capture client exact zeros
 * when a call or another app holds the microphone (docs/PLAN.md section 7.3).
 *
 * - [inUse]: the first [IN_USE_WINDOW_SAMPLES] samples (half a second) were all exactly zero. A few warm-up
 *   buffers of zeros at the start are normal on some devices; half a second is not.
 * - [lost]: after real input, [LOST_RUN_SAMPLES] consecutive zeros (one second): the microphone was taken away.
 *
 * Not thread-safe; fed by one capture loop.
 */
internal class ZeroInputWatch {
    private var samplesSeen = 0L
    private var zeroRun = 0L

    /** At least one sample was not zero. */
    var sawSignal: Boolean = false
        private set

    /** The first half second was all zeros (decided once that many samples arrived). */
    val inUse: Boolean get() = !sawSignal && samplesSeen >= IN_USE_WINDOW_SAMPLES

    /** Input stopped after real audio: a run of zeros at least [LOST_RUN_SAMPLES] long. */
    val lost: Boolean get() = sawSignal && zeroRun >= LOST_RUN_SAMPLES

    /** Feeds the first [count] samples of [samples]. */
    fun accept(samples: ShortArray, count: Int) {
        for (index in 0 until count) {
            if (samples[index].toInt() == 0) {
                zeroRun++
            } else {
                zeroRun = 0
                sawSignal = true
            }
        }
        samplesSeen += count
    }

    companion object {
        /** Half a second at 16 kHz. */
        const val IN_USE_WINDOW_SAMPLES: Long = 8_000

        /** One second at 16 kHz. */
        const val LOST_RUN_SAMPLES: Long = 16_000
    }
}
