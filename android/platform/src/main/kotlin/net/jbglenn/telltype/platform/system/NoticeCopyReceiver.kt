package net.jbglenn.telltype.platform.system

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent

/**
 * The Copy action of a "Transcribed later" notification: puts the text on the clipboard (flagged sensitive) and
 * removes the notification. Not exported; reached only through the notification's immutable pending intent.
 */
public class NoticeCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(SensitiveClip.of(text))
        val id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, NO_ID)
        if (id != NO_ID) context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    internal companion object {
        /** The text to copy. */
        const val EXTRA_TEXT = "net.jbglenn.telltype.platform.extra.TEXT"

        /** The notification to remove afterwards. */
        const val EXTRA_NOTIFICATION_ID = "net.jbglenn.telltype.platform.extra.NOTIFICATION_ID"

        private const val NO_ID = -1
    }
}
