package net.jbglenn.telltype.platform.system

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.domain.port.NotifierPort
import net.jbglenn.telltype.platform.R

/**
 * [NotifierPort] over `NotificationManager`. Does nothing visible without the notification permission. Dictated
 * text appears only in the private version (hidden on a secure lock screen) and in the Copy action, which goes to
 * [NoticeCopyReceiver]. Pending intents are immutable.
 */
internal class AndroidNotifier(private val context: Context, private val dispatchers: DispatcherProvider) :
    NotifierPort {
    override suspend fun post(notice: Notice) {
        withContext(dispatchers.io) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager != null && canPost(manager)) {
                NotificationChannels.ensure(context)
                val spec = NoticeContent.of(notice)
                manager.notify(spec.id, build(spec))
            }
        }
    }

    override suspend fun cancel(notice: Notice) {
        withContext(dispatchers.io) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NoticeContent.of(notice).id)
        }
    }

    private fun canPost(manager: NotificationManager): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.areNotificationsEnabled()

    private fun build(spec: NoticeSpec): Notification {
        val message = spec.count?.let { context.resources.getQuantityString(spec.text, it, it) }
            ?: context.getString(spec.text)
        val builder = base(spec, message).setContentIntent(contentIntent(spec))
        spec.privateText?.let { text ->
            builder.setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(base(spec, message).build())
                .addAction(copyAction(spec, text))
        }
        return builder.build()
    }

    private fun base(spec: NoticeSpec, message: String): Notification.Builder =
        Notification.Builder(context, spec.channel)
            .setSmallIcon(R.drawable.ic_telltype_notification)
            .setContentTitle(context.getString(spec.title))
            .setContentText(message)
            .setAutoCancel(true)

    private fun contentIntent(spec: NoticeSpec): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val target = spec.fix?.let { SettingsIntents.forFix(it, context.packageName, launch) } ?: launch
        return target?.let { PendingIntent.getActivity(context, spec.id, it, PENDING_FLAGS) }
    }

    private fun copyAction(spec: NoticeSpec, text: String): Notification.Action {
        val intent = Intent(context, NoticeCopyReceiver::class.java)
            .putExtra(NoticeCopyReceiver.EXTRA_TEXT, text)
            .putExtra(NoticeCopyReceiver.EXTRA_NOTIFICATION_ID, spec.id)
        val pending = PendingIntent.getBroadcast(context, spec.id, intent, PENDING_FLAGS)
        return Notification.Action.Builder(null, context.getString(R.string.notice_action_copy), pending).build()
    }

    private companion object {
        const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
