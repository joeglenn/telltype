package net.jbglenn.telltype.ui.overlay

import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformTest {
    @Test
    fun keepsTheLastSevenLevelsClamped() {
        val levels = WaveformLevels()

        (1..9).forEach { levels.push(it / 10f) }
        levels.push(2f)

        assertEquals(BubbleTokens.WAVEFORM_BARS, levels.bars.size)
        assertArrayEquals(floatArrayOf(0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f), levels.bars, 0.0001f)
    }

    @Test
    fun throttleLetsAtMostSixtyLevelsPerSecondThrough() {
        val throttle = LevelThrottle()
        val frameNanos = 1_000_000_000L / 120

        val accepted = (0 until 120).count { throttle.accept(it * frameNanos) }

        assertEquals(60, accepted)
    }
}
