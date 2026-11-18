package net.jbglenn.telltype.platform.system

import android.media.ToneGenerator
import android.os.VibrationEffect
import net.jbglenn.telltype.core.model.session.FeedbackCue

/**
 * The feedback for each cue (Appendix H: confirm on start and on stop, reject on a problem; a light tick when text
 * arrives). Pure.
 */
internal object FeedbackEffects {
    /** The predefined vibration for [cue]. */
    fun vibration(cue: FeedbackCue): Int = when (cue) {
        FeedbackCue.RECORDING_STARTED, FeedbackCue.RECORDING_STOPPED -> VibrationEffect.EFFECT_CLICK
        FeedbackCue.DONE -> VibrationEffect.EFFECT_TICK
        FeedbackCue.PROBLEM -> VibrationEffect.EFFECT_DOUBLE_CLICK
    }

    /** The optional tone for [cue] (start and stop only), or null. */
    fun tone(cue: FeedbackCue): Int? = when (cue) {
        FeedbackCue.RECORDING_STARTED -> ToneGenerator.TONE_PROP_BEEP
        FeedbackCue.RECORDING_STOPPED -> ToneGenerator.TONE_PROP_ACK
        FeedbackCue.DONE, FeedbackCue.PROBLEM -> null
    }
}
