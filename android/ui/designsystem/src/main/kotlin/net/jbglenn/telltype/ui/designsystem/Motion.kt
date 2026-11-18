package net.jbglenn.telltype.ui.designsystem

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Motion and timing tokens (Appendix H, section 5.2). */
object MotionTokens {
    /** Stiffness of the Ready ↔ Listening width spring: settles in about 250 ms. */
    const val RESIZE_STIFFNESS: Float = 400f

    /** Damping of the width spring: about 1.5% overshoot, inside the 4% limit. */
    const val RESIZE_DAMPING_RATIO: Float = 0.8f

    /** State colour and icon cross-fade, in milliseconds. */
    const val CROSS_FADE_MILLIS: Int = 150

    /** Done check mark, in milliseconds; the chips follow it. */
    const val DONE_CHECK_MILLIS: Int = 300

    /** Every transition when the system asks for reduced motion, in milliseconds. */
    const val REDUCED_MILLIS: Int = 100

    /** Working shows its stage label only after this long (section 5.2). */
    val WorkingLabelDelay: Duration = 1.5.seconds

    /** Listening shows its timer only after this long (section 5.2). */
    val ListeningTimerDelay: Duration = 10.seconds

    /** The check mark is shown alone for this long before Done's chips appear. */
    val DoneCheckDuration: Duration = DONE_CHECK_MILLIS.milliseconds

    /** A press held this long becomes hold-to-talk. */
    val HoldThreshold: Duration = 350.milliseconds

    /**
     * A press that can start a session starts it once the finger has stayed within the touch slop this long
     * (decision D57): a drag almost always moves before that, and hold-to-talk loses less of its first word.
     */
    val StartThreshold: Duration = 150.milliseconds

    /** One full sweep of the Working shimmer, in milliseconds. */
    const val SHIMMER_MILLIS: Int = 1200
}

/**
 * The motion in effect: Material springs and 150 ms cross-fades normally, 100 ms cross-fades only when the system
 * animation scale is off (Appendix H, reduced motion). With the scale at zero Compose also shortens these to an
 * instant change, so nothing moves.
 *
 * @property reduced the reduced-motion variant is in effect.
 */
@Immutable
class TelltypeMotion(val reduced: Boolean) {
    /** Size changes, such as the bubble widening from Ready to Listening. */
    fun <T> resize(): FiniteAnimationSpec<T> = if (reduced) {
        tween(MotionTokens.REDUCED_MILLIS)
    } else {
        spring(dampingRatio = MotionTokens.RESIZE_DAMPING_RATIO, stiffness = MotionTokens.RESIZE_STIFFNESS)
    }

    /** Colour and icon changes. */
    fun <T> fade(): FiniteAnimationSpec<T> =
        tween(if (reduced) MotionTokens.REDUCED_MILLIS else MotionTokens.CROSS_FADE_MILLIS)

    /** The Done check mark. */
    fun <T> check(): FiniteAnimationSpec<T> =
        tween(if (reduced) MotionTokens.REDUCED_MILLIS else MotionTokens.DONE_CHECK_MILLIS)

    /** Content swaps (`AnimatedContent`): always a cross-fade, never a slide or a bounce. */
    fun crossFade(): ContentTransform = fadeIn(fade()) togetherWith fadeOut(fade())

    /** Whether looping decoration (the Working shimmer) may move. */
    val loops: Boolean get() = !reduced
}

/** The motion for the current system setting, read once per context. */
@Composable
fun rememberTelltypeMotion(): TelltypeMotion {
    val context = LocalContext.current
    return remember(context) { TelltypeMotion(reduced = animationsOff(context)) }
}

private fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
