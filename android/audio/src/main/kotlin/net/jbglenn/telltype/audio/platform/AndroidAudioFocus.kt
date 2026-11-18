package net.jbglenn.telltype.audio.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Exclusive transient focus with speech attributes: other apps pause (not just duck) and resume after the
 * recording. Losing focus permanently or transiently counts as lost; a duck request does not.
 */
internal class AndroidAudioFocus @Inject constructor(@ApplicationContext context: Context) : AudioFocus {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun request(onLost: () -> Unit): Registration? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change -> if (isLoss(change)) onLost() }
            .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return null
        val released = AtomicBoolean(false)
        return Registration {
            if (released.compareAndSet(false, true)) audioManager.abandonAudioFocusRequest(request)
        }
    }

    internal companion object {
        /** Focus changes that end the recording. */
        fun isLoss(change: Int): Boolean =
            change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }
}
