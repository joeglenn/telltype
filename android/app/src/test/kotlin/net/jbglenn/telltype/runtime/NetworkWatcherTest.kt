package net.jbglenn.telltype.runtime

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** The default-network watcher: probes once at start and again on every change of the default network. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NetworkWatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val health = FakeServerHealthSource()
    private val watcher = NetworkWatcher(context, health, RecordingLogger())
    private val wifi = ShadowNetwork.newInstance(WIFI)
    private val mobile = ShadowNetwork.newInstance(MOBILE)

    private fun TestScope.watching() =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { watcher.watch() }

    private fun callbacks() = shadowOf(manager).networkCallbacks

    @Test
    fun `the first network is probed once`() = runTest {
        watching()
        callbacks().forEach { it.onAvailable(wifi) }
        runCurrent()
        assertEquals(1, health.probeCall.calls.size)
    }

    @Test
    fun `a new default network is probed again`() = runTest {
        watching()
        callbacks().forEach { it.onAvailable(wifi) }
        runCurrent()
        callbacks().forEach { it.onAvailable(mobile) }
        runCurrent()
        assertEquals(2, health.probeCall.calls.size)
    }

    @Test
    fun `the same network reported again changes nothing, losing it probes again`() = runTest {
        watching()
        callbacks().forEach { it.onAvailable(wifi) }
        callbacks().forEach { it.onAvailable(wifi) }
        runCurrent()
        assertEquals(1, health.probeCall.calls.size)
        callbacks().forEach { it.onLost(wifi) }
        runCurrent()
        assertEquals(2, health.probeCall.calls.size)
    }

    @Test
    fun `cancelling the watch unregisters the callback`() = runTest {
        val job = watching()
        runCurrent()
        assertEquals(1, callbacks().size)
        job.cancel()
        runCurrent()
        assertEquals(0, callbacks().size)
    }

    private companion object {
        const val WIFI = 100
        const val MOBILE = 101
    }
}
