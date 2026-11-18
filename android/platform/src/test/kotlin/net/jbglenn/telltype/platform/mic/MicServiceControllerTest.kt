package net.jbglenn.telltype.platform.mic

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import net.jbglenn.telltype.platform.system.NotificationChannels
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MicServiceControllerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val status = MicServiceStatus()

    private fun TestScope.controller() = MicServiceController(
        context = app,
        bridge = AccessibilityServiceBridge(),
        status = status,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        logger = RecordingLogger(),
        timeout = 3.seconds,
    )

    @Test
    fun `without the microphone permission nothing is started`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)

        assertEquals(Outcome.Err(MicrophonePermissionMissing), controller().start())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `a start succeeds when the service reports the foreground state`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val result = async { controller().start() }
        runCurrent()

        assertEquals(MicService.ACTION_START, shadowOf(app).nextStartedService.action)
        status.report(MicServiceState.Running)
        assertEquals(Outcome.Ok(Unit), result.await())
    }

    @Test
    fun `a refused foreground start is a recorder failure`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val result = async { controller().start() }
        runCurrent()

        status.report(MicServiceState.Refused("android.app.ForegroundServiceStartNotAllowedException"))
        assertEquals(Outcome.Err(RecorderFailed), result.await())
        assertEquals(MicServiceState.Stopped, status.current.value)
    }

    @Test
    fun `a service that never answers is a recorder failure after the timeout`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertEquals(Outcome.Err(RecorderFailed), controller().start())
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun `a running service is not started again`() = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        status.report(MicServiceState.Running)

        assertEquals(Outcome.Ok(Unit), controller().start())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `the service enters the foreground with a recording notification and reports it`() {
        val intent = Intent(app, MicService::class.java).setAction(MicService.ACTION_START)
        val controller = Robolectric.buildService(MicService::class.java, intent).create().startCommand(0, 1)

        assertEquals(MicServiceState.Running, MicServiceStatus.shared.current.value)
        val notification = shadowOf(controller.get()).lastForegroundNotification
        assertEquals(NotificationChannels.RECORDING, notification.channelId)
        controller.destroy()
        assertEquals(MicServiceState.Stopped, MicServiceStatus.shared.current.value)
    }
}
