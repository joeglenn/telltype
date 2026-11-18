package net.jbglenn.telltype.platform.mic

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import net.jbglenn.telltype.platform.R
import net.jbglenn.telltype.platform.system.NotificationChannels

/**
 * The foreground service of type microphone (docs/PLAN.md section 7.3, Appendix F). It records nothing: while it is
 * in the foreground the process may use the microphone, and `:audio` opens the recorder. Started on demand with
 * [ACTION_START] through [MicServiceController] (from the accessibility service), stopped with `stopService`. Its
 * notification is visible only while it runs. The result of each start goes to [MicServiceStatus].
 */
public class MicService : Service() {
    private val status: MicServiceStatus = MicServiceStatus.shared

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) status.report(enterForeground())
        if (status.current.value != MicServiceState.Running) stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        status.report(MicServiceState.Stopped)
        super.onDestroy()
    }

    private fun enterForeground(): MicServiceState = try {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        MicServiceState.Running
    } catch (e: SecurityException) {
        MicServiceState.Refused(e.javaClass.name)
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is an IllegalStateException.
        MicServiceState.Refused(e.javaClass.name)
    }

    private fun notification(): Notification {
        NotificationChannels.ensure(this)
        return Notification.Builder(this, NotificationChannels.RECORDING)
            .setSmallIcon(R.drawable.ic_telltype_notification)
            .setContentTitle(getString(R.string.mic_notification_title))
            .setContentText(getString(R.string.mic_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    internal companion object {
        /** Starts the foreground state. */
        const val ACTION_START = "net.jbglenn.telltype.platform.action.START_MICROPHONE"

        private const val NOTIFICATION_ID = 0x4d1c
    }
}
