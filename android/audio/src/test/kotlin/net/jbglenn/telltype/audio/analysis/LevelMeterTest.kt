package net.jbglenn.telltype.audio.analysis

import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.audio.Signals
import net.jbglenn.telltype.core.model.audio.AudioLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelMeterTest {
    private fun levelOf(samples: ShortArray): Float = LevelMeter().apply { update(samples, samples.size) }.latest()

    @Test
    fun silenceIsZero() {
        assertEquals(0f, levelOf(ShortArray(320)), 0f)
    }

    @Test
    fun fullScaleSineIsItsRms() {
        val samples = Signals.samples(Signals.fullScaleSine(hz = 500.0), 320)

        assertEquals((32_767 / sqrt(2.0) / 32_768).toFloat(), levelOf(samples), 0.001f)
    }

    @Test
    fun dcOffsetCountsAtItsMagnitude() {
        assertEquals(0.25f, levelOf(Signals.samples(Signals.dc(8_192), 320)), 1e-6f)
        assertEquals(0.25f, levelOf(Signals.samples(Signals.dc(-8_192), 320)), 1e-6f)
        assertEquals(1f, levelOf(Signals.samples(Signals.dc(Short.MIN_VALUE), 320)), 0f)
    }

    @Test
    fun onlyTheGivenCountIsMeasured() {
        val samples = ShortArray(320) { if (it < 160) 16_384 else 0 }

        val meter = LevelMeter().apply { update(samples, 160) }

        assertEquals(0.5f, meter.latest(), 1e-6f)
    }

    @Test
    fun ticksEmitEachNewLevelAtMostSixtyTimesPerSecond() = runTest {
        val meter = LevelMeter()
        val emitted = mutableListOf<AudioLevel>()
        backgroundScope.launch { meter.ticks().collect(emitted::add) }
        val loud = Signals.samples(Signals.dc(16_384), 80)

        repeat(200) {
            meter.update(loud, loud.size)
            delay(5.milliseconds)
        }

        assertTrue("got ${emitted.size} levels in one second", emitted.size in 50..LevelMeter.MAX_RATE_HZ)
        assertTrue(emitted.all { it.value == 0.5f })
    }

    @Test
    fun ticksStayQuietUntilANewBufferArrives() = runTest {
        val meter = LevelMeter()
        val emitted = mutableListOf<AudioLevel>()
        backgroundScope.launch { meter.ticks().collect(emitted::add) }

        delay(1.seconds)
        assertEquals(0, emitted.size)

        meter.update(ShortArray(10) { 3_277 }, 10)
        delay(1.seconds)
        assertEquals(1, emitted.size)
        assertEquals(0.1f, emitted.single().value, 1e-4f)
    }
}
