package net.jbglenn.telltype.platform.system

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.domain.port.HapticsPort
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * [HapticsPort]: the vibration of [FeedbackEffects] when "haptics" is on (with touch usage, so the system's touch
 * feedback switch applies too) and its tone when "sound cues" is on. Fire and forget: each cue runs in [scope].
 */
internal class AndroidHaptics(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : HapticsPort {
    override fun play(cue: FeedbackCue) {
        scope.launch(dispatchers.default) {
            val bubble = settings.current().bubble
            if (bubble.haptics) vibrate(cue)
            if (bubble.soundCues) FeedbackEffects.tone(cue)?.let { tone(it) }
        }
    }

    private fun vibrate(cue: FeedbackCue) {
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        if (vibrator?.hasVibrator() == true) {
            vibrator.vibrate(
                VibrationEffect.createPredefined(FeedbackEffects.vibration(cue)),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
            )
        }
    }

    private suspend fun tone(tone: Int) {
        val generator = createToneGenerator() ?: return
        try {
            generator.startTone(tone, TONE_MILLIS)
            delay(TONE_MILLIS.toLong() + RELEASE_GRACE_MILLIS)
        } finally {
            generator.release()
        }
    }

    // ToneGenerator's constructor signals a missing audio output with a plain RuntimeException ("Init failed").
    @Suppress("TooGenericExceptionCaught")
    private fun createToneGenerator(): ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_SYSTEM, TONE_VOLUME)
    } catch (e: RuntimeException) {
        logger.warn(TAG, e) { "no tone generator" }
        null
    }

    private companion object {
        val TAG = LogTag("Haptics")
        const val TONE_VOLUME = 60
        const val TONE_MILLIS = 120
        const val RELEASE_GRACE_MILLIS = 50L
    }
}
