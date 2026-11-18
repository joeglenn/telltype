package net.jbglenn.telltype.ui.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The size the bubble's body is drawn at while it changes shape (the Ready circle widening into the Listening pill).
 *
 * Only the drawing animates: the layout takes the new size at once, so the overlay window is resized once and the
 * buttons' accessibility nodes and touch targets are where they will stay from the first frame they exist. A growing
 * layout resized the window on every frame; the window frame lags the layout by a frame or more (in a fresh process
 * for about a second), and meanwhile ✕ and ✓ were reported, and taps were dispatched, beside where they were drawn.
 *
 * @param spec the animation of the drawn width and height (a spring, or a short tween with reduced motion).
 */
@Stable
internal class SizeReveal(private val spec: AnimationSpec<Float>) {
    private val width = Animatable(0f)
    private val height = Animatable(0f)

    /** The drawn width in pixels; 0 before the first layout. */
    val drawnWidth: Float get() = width.value

    /** The drawn height in pixels; 0 before the first layout. */
    val drawnHeight: Float get() = height.value

    /** Animates the drawn size to the laid-out [size]; the first size is taken at once (nothing to grow from). */
    suspend fun follow(size: IntSize) {
        val first = width.value == 0f || height.value == 0f
        coroutineScope {
            launch { if (first) width.snapTo(size.width.toFloat()) else width.animateTo(size.width.toFloat(), spec) }
            launch {
                if (first) height.snapTo(size.height.toFloat()) else height.animateTo(size.height.toFloat(), spec)
            }
        }
    }
}

/**
 * Draws this element clipped to [reveal]'s current size, aligned to the docked edge ([fromEnd]: the right edge) and
 * centred vertically, as a pill. [margin] leaves room for the shadow outside the element. Touches are not clipped.
 */
internal fun Modifier.drawRevealed(reveal: SizeReveal, fromEnd: Boolean, margin: Dp): Modifier = drawWithContent {
    val width = reveal.drawnWidth
    val height = reveal.drawnHeight
    val unknown = width <= 0f || height <= 0f
    val whole = width >= size.width && height >= size.height
    if (unknown || whole) {
        drawContent()
    } else {
        val extra = margin.toPx()
        val left = if (fromEnd) size.width - width else 0f
        val top = (size.height - height) / 2
        val radius = height / 2 + extra
        val outline = Path().apply {
            addRoundRect(
                RoundRect(left - extra, top - extra, left + width + extra, top + height + extra, CornerRadius(radius)),
            )
        }
        clipPath(outline) { this@drawWithContent.drawContent() }
    }
}
