package net.jbglenn.telltype.ui.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Bubble geometry (Appendix H). */
object BubbleTokens {
    /** Visual diameter of the Ready circle. */
    val ReadySize: Dp = 44.dp

    /** Touch target around every bubble control. */
    val TouchTarget: Dp = 48.dp

    /** Opacity of the Ready circle while it is not touched. */
    const val READY_OPACITY: Float = 0.7f

    /** Width of the Listening and Working pill (it grows when large text needs more). */
    val PillWidth: Dp = 168.dp

    /** Height of the pill. */
    val PillHeight: Dp = 48.dp

    /** Widest the bubble gets, so a long message wraps instead of covering the screen. */
    val MaxWidth: Dp = 320.dp

    /** Height of the action chips. */
    val ChipHeight: Dp = 32.dp

    /** Gap between chips, and between the chips and the pill. */
    val ChipGap: Dp = 8.dp

    /** Material elevation level 3. */
    val Elevation: Dp = 6.dp

    /** Distance from the screen edge the bubble snaps to. */
    val EdgeMargin: Dp = 8.dp

    /** Number of waveform bars. */
    const val WAVEFORM_BARS: Int = 7

    /** Most waveform updates per second. */
    const val MAX_LEVEL_UPDATES_PER_SECOND: Int = 60

    /** Waveform bar width. */
    val WaveformBarWidth: Dp = 3.dp

    /** How far a held finger slides away from the bubble before releasing cancels. */
    val SlideCancelDistance: Dp = 72.dp

    /** Height of the band at the bottom of the screen where dropping the bubble snoozes it. */
    val SnoozeZoneHeight: Dp = 120.dp

    /** Size of the offline mark on the Ready circle. */
    val OfflineMarkSize: Dp = 16.dp
}

/** The bubble's visual states, each with its colour role (Appendix H, colour roles). */
enum class BubbleTone {
    /** Ready: surface container high / on surface. */
    READY,

    /** Listening (and Arming): primary container. */
    LISTENING,

    /** Working: secondary container. */
    WORKING,

    /** Done: tertiary container. */
    DONE,

    /** Problem: the amber warning pair (Color.kt). */
    PROBLEM,

    /** Hold-to-talk with the finger slid away ("release to cancel"): error container. */
    CANCELLING,

    /** Unavailable: surface container / on surface variant. */
    UNAVAILABLE,
}

/**
 * A container colour and the colour of everything drawn on it.
 *
 * @property container the pill's fill.
 * @property content icons and text on it.
 */
@Immutable
data class BubbleColors(val container: Color, val content: Color)

/** The colours of [tone] in this scheme. */
fun ColorScheme.bubbleColors(tone: BubbleTone): BubbleColors = when (tone) {
    BubbleTone.READY -> BubbleColors(surfaceContainerHigh, onSurface)
    BubbleTone.LISTENING -> BubbleColors(primaryContainer, onPrimaryContainer)
    BubbleTone.WORKING -> BubbleColors(secondaryContainer, onSecondaryContainer)
    BubbleTone.DONE -> BubbleColors(tertiaryContainer, onTertiaryContainer)
    BubbleTone.PROBLEM -> warningColors().let { BubbleColors(it.container, it.content) }
    BubbleTone.CANCELLING -> BubbleColors(errorContainer, onErrorContainer)
    BubbleTone.UNAVAILABLE -> BubbleColors(surfaceContainer, onSurfaceVariant)
}
