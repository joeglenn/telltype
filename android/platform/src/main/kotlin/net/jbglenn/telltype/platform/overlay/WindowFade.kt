package net.jbglenn.telltype.platform.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * The bubble's quick fade in and out (board U4): the alpha of the window's own content, never a window animation
 * (decision D39 keeps those off). A fade starts from the alpha shown now, so a show during a fade-out reverses it
 * (and takes only as long as the way back). With the system's animations removed (animator duration scale 0) the
 * alpha jumps at once and a fade-out ends at once. Main thread only.
 *
 * @param view the content whose alpha fades.
 * @param animatorsEnabled whether the system plays animations now (false with "Remove animations").
 */
internal class WindowFade(
    private val view: View,
    private val animatorsEnabled: () -> Boolean = ValueAnimator::areAnimatorsEnabled,
) {
    private var running: ValueAnimator? = null
    private var target = 1f

    /** Whether the content is fading out (or has faded out and waits for its removal). */
    val fadingOut: Boolean get() = target == 0f

    /** Fades the content in from transparent (it is appearing). */
    fun appear() {
        view.alpha = 0f
        fadeTo(1f) {}
    }

    /** Fades the content back in from wherever it is now (a show during a fade-out). */
    fun reverse() {
        fadeTo(1f) {}
    }

    /** Fades the content out, then calls [onFaded] (at once without animations). */
    fun disappear(onFaded: () -> Unit) {
        fadeTo(0f, onFaded)
    }

    /** Stops any fade and leaves the content opaque (the window is being removed or reused). */
    fun cancel() {
        stop()
        target = 1f
        view.alpha = 1f
    }

    private fun fadeTo(alpha: Float, onEnd: () -> Unit) {
        stop()
        target = alpha
        val from = view.alpha
        if (!animatorsEnabled() || from == alpha) {
            view.alpha = alpha
            onEnd()
            return
        }
        running = ValueAnimator.ofFloat(from, alpha).apply {
            duration = (FADE_MILLIS * abs(alpha - from)).toLong().coerceAtLeast(1L)
            interpolator = DecelerateInterpolator()
            addUpdateListener { view.alpha = it.animatedValue as Float }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (running === animation) {
                            running = null
                            view.alpha = alpha
                            onEnd()
                        }
                    }
                },
            )
            start()
        }
    }

    private fun stop() {
        val current = running ?: return
        running = null
        current.removeAllUpdateListeners()
        current.cancel()
    }

    companion object {
        /** A full fade, in milliseconds, before the system's animator duration scale. */
        const val FADE_MILLIS: Long = 120L
    }
}
