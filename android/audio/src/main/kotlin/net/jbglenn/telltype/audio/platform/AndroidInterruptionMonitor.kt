package net.jbglenn.telltype.audio.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import net.jbglenn.telltype.core.model.audio.Interruption

/**
 * Screen off (the `ACTION_SCREEN_OFF` broadcast, which only a runtime receiver gets) and calls (the audio mode moving
 * to ringing, in-call or in-communication, which covers phone and VoIP calls without the phone-state permission).
 */
internal class AndroidInterruptionMonitor @Inject constructor(@ApplicationContext private val context: Context) :
    InterruptionMonitor {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun watch(onInterruption: (Interruption) -> Unit): Registration {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) onInterruption(Interruption.SCREEN_OFF)
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        val modeListener = AudioManager.OnModeChangedListener { mode ->
            if (isCallMode(mode)) onInterruption(Interruption.CALL_STARTED)
        }
        audioManager.addOnModeChangedListener(context.mainExecutor, modeListener)
        val released = AtomicBoolean(false)
        return Registration {
            if (released.compareAndSet(false, true)) {
                audioManager.removeOnModeChangedListener(modeListener)
                context.unregisterReceiver(receiver)
            }
        }
    }

    internal companion object {
        /** Audio modes that mean a call is ringing or running. */
        fun isCallMode(mode: Int): Boolean = mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING
    }
}
