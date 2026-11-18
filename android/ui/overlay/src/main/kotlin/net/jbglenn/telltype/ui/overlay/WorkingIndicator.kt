package net.jbglenn.telltype.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.MotionTokens
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion

/**
 * The waveform turned into a progress shimmer: the bars ripple in a travelling wave. With reduced motion the bars
 * stand still at a middle height. The phase is read only while drawing.
 */
@Composable
internal fun WorkingIndicator(color: Color, motion: TelltypeMotion, modifier: Modifier = Modifier) {
    if (motion.loops) {
        val transition = rememberInfiniteTransition(label = "working")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(MotionTokens.SHIMMER_MILLIS, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "phase",
        )
        Canvas(modifier = modifier) { drawBars(rippleHeights(phase), color) }
    } else {
        Canvas(modifier = modifier) { drawBars(StillHeights, color) }
    }
}

private fun rippleHeights(phase: Float): FloatArray = FloatArray(BubbleTokens.WAVEFORM_BARS) { index ->
    val angle = 2 * PI * (phase - index.toFloat() / BubbleTokens.WAVEFORM_BARS)
    (RippleDefaults.RIPPLE_BASE + RippleDefaults.RIPPLE_DEPTH * sin(angle)).toFloat()
}

private val StillHeights: FloatArray = FloatArray(BubbleTokens.WAVEFORM_BARS) { RippleDefaults.STILL_LEVEL }

/** Fixed values used in this file. */
private object RippleDefaults {
    const val RIPPLE_BASE = 0.25
    const val RIPPLE_DEPTH = 0.2
    const val STILL_LEVEL = 0.2f
}
