package net.jbglenn.telltype.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sqrt
import kotlinx.coroutines.flow.Flow
import net.jbglenn.telltype.core.model.audio.AudioLevel
import net.jbglenn.telltype.ui.designsystem.BubbleTokens

/**
 * The last [BubbleTokens.WAVEFORM_BARS] input levels, newest last. Written by the level collector and read only
 * inside [Waveform]'s draw block, so a new level redraws the bars without recomposing the bubble.
 */
@Stable
class WaveformLevels {
    private var snapshot by mutableStateOf(FloatArray(BubbleTokens.WAVEFORM_BARS))

    /** Appends [level] (0 to 1) and drops the oldest. */
    fun push(level: Float) {
        val previous = snapshot
        snapshot = FloatArray(previous.size) { index ->
            if (index == previous.lastIndex) level.coerceIn(0f, 1f) else previous[index + 1]
        }
    }

    /** The levels to draw. Read it only in the draw phase. */
    internal val bars: FloatArray get() = snapshot
}

/**
 * Lets a level through at most [maxPerSecond] times per second (Appendix H: 60). The audio side already
 * conflates; this also caps displays that refresh faster than 60 Hz.
 */
class LevelThrottle(maxPerSecond: Int = BubbleTokens.MAX_LEVEL_UPDATES_PER_SECOND) {
    private val minIntervalNanos = NANOS_PER_SECOND / maxPerSecond
    private var lastAccepted: Long? = null

    /** Whether a level arriving at [nowNanos] (frame time) should be shown. */
    fun accept(nowNanos: Long): Boolean {
        val last = lastAccepted
        if (last != null && nowNanos - last < minIntervalNanos) return false
        lastAccepted = nowNanos
        return true
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}

/**
 * Collects [levels] into a [WaveformLevels] for as long as this is in the composition, one level per frame at
 * most and never more than 60 per second.
 */
@Composable
fun rememberWaveformLevels(levels: Flow<AudioLevel>): WaveformLevels {
    val waveform = remember { WaveformLevels() }
    LaunchedEffect(levels, waveform) {
        val throttle = LevelThrottle()
        levels.collect { level ->
            val frameTime = withFrameNanos { it }
            if (throttle.accept(frameTime)) waveform.push(level.value)
        }
    }
    return waveform
}

/** Seven rounded bars whose heights follow the input level. All state is read while drawing. */
@Composable
internal fun Waveform(levels: WaveformLevels, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) { drawBars(levels.bars, color) }
}

/** Draws [heights] (0 to 1) as centred bars spread across the width. */
internal fun DrawScope.drawBars(heights: FloatArray, color: Color) {
    val barWidth = BubbleTokens.WaveformBarWidth.toPx()
    val gap = (size.width - barWidth * heights.size) / (heights.size - 1).coerceAtLeast(1)
    val minHeight = barWidth
    heights.forEachIndexed { index, level ->
        val height = (minHeight + (size.height - minHeight) * sqrt(level)).coerceAtMost(size.height)
        drawRoundRect(
            color = color,
            topLeft = Offset(x = index * (barWidth + gap), y = (size.height - height) / 2f),
            size = Size(barWidth, height),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}
