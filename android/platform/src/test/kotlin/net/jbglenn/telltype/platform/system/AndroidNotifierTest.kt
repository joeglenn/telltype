package net.jbglenn.telltype.platform.system

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidNotifierTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)

    @Test
    fun `without the notification permission nothing is posted`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        AndroidNotifier(
            app,
            TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        ).post(Notice.AccessibilityOff)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a later transcription shows its text only privately and offers Copy`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidNotifier(app, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))

        notifier.post(Notice.TranscribedLater(SessionId("s1"), "Meet at noon."))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationChannels.RESULTS, posted.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("Meet at noon.", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val public = posted.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(public.contains("noon"))
        assertEquals("Copy", posted.actions.single().title)
        assertTrue(posted.actions.single().actionIntent.isImmutable)
        assertEquals(3, manager.notificationChannels.size)
    }

    @Test
    fun `cancel removes the notice of that kind and leaves the others`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifier = AndroidNotifier(app, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))
        notifier.post(Notice.AccessibilityOff)
        notifier.post(Notice.DictationsRecovered(1))

        notifier.cancel(Notice.AccessibilityOff)
        notifier.cancel(Notice.AccessibilityOff)

        val left = shadowOf(manager).allNotifications.single()
        assertEquals("Recovered dictations", left.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `the copy action puts the text on the clipboard, flagged sensitive, and removes the notification`() {
        val clipboard = app.getSystemService(ClipboardManager::class.java)
        manager.notify(42, Notification.Builder(app, NotificationChannels.RESULTS).build())

        NoticeCopyReceiver().onReceive(
            app,
            Intent(app, NoticeCopyReceiver::class.java)
                .putExtra(NoticeCopyReceiver.EXTRA_TEXT, "Meet at noon.")
                .putExtra(NoticeCopyReceiver.EXTRA_NOTIFICATION_ID, 42),
        )

        assertEquals("Meet at noon.", clipboard.primaryClip?.getItemAt(0)?.text)
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
