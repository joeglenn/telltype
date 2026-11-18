package net.jbglenn.telltype.platform.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import net.jbglenn.telltype.platform.R

/** The app's notification channels (Appendix F: recording notification, queued results, problems). */
internal object NotificationChannels {
    /** The microphone service's notification while recording: low importance, silent. */
    const val RECORDING = "recording"

    /** Results that arrived later and recovered dictations. */
    const val RESULTS = "results"

    /** Problems the bubble cannot show: accessibility off, Advanced Protection, problems while hidden. */
    const val PROBLEMS = "problems"

    /** Creates the channels; creating an existing channel only updates its name. */
    fun ensure(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    RECORDING,
                    context.getString(R.string.channel_recording),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    RESULTS,
                    context.getString(R.string.channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    PROBLEMS,
                    context.getString(R.string.channel_problems),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }
}
