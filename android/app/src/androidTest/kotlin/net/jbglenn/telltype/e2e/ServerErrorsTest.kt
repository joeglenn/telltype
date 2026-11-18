package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Error-table rows against the real network path: the server's gateway, a port where nothing listens (unreachable), and
 * a wrong key (401 from the real gateway). The clip microphone speaks; the real engines upload.
 */
@RunWith(AndroidJUnit4::class)
class ServerErrorsTest {
    @get:Rule
    val shots = Scenario.failureShots

    private val savedKey = Arrange.settings().server.key

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(false)
    }

    @After
    fun restore() {
        Arrange.settings { it.copy(server = it.server.copy(address = Arrange.GATE, key = savedKey)) }
    }

    @Test
    fun unreachableServerQueuesTheDictationAndTheBackgroundJobTranscribesItLater() {
        Arrange.address(DEAD_PORT)
        val queuedBefore = Arrange.pendingJobs().size
        Scenario.field()
        Scenario.dictate()
        val state = Bubble.expectState("Problem", limit = Scenario.LONG_WAIT_MS)
        Device.screenshot("et-unreachable")
        assertEquals("Problem. Server not answering. Saved for later", state)
        assertEquals("one dictation queued", queuedBefore + 1, Arrange.pendingJobs().size)
        assertTrue("the recording is kept", Device.recordings().isNotEmpty())
        Scenario.assertNotTyped()
        Arrange.address(Arrange.GATE)
        runPendingJobs()
        assertTrue(
            "the queue is emptied by the background job",
            Scenario.eventually(Scenario.LONG_WAIT_MS) {
                runPendingJobs()
                Arrange.pendingJobs().size == queuedBefore
            },
        )
        val entry = checkNotNull(Arrange.latestHistory()) { "no History entry" }
        Log.i(
            TAG,
            "unreachable later: ${entry.finalText.length} chars, error ${entry.errorCode}, via ${entry.insertedVia}",
        )
        assertTrue("History has the transcript", entry.finalText.contains("wish"))
        val notification = Device.shell("dumpsys notification --noredact")
            .lines().filter { it.contains(Device.app.packageName) && it.contains("NotificationRecord") }
        Log.i(TAG, "unreachable: history via ${entry.insertedVia}, notifications $notification")
        Device.shell("cmd statusbar expand-notifications")
        Device.screenshot("et-unreachable-later-notification")
        Device.shell("cmd statusbar collapse")
        assertTrue("a notification offers the text", notification.isNotEmpty())
        Scenario.assertNotTyped(limit = 0)
    }

    @Test
    fun rejectedKeyShowsFixAndKeepsTheAudio() {
        Arrange.settings { it.copy(server = it.server.copy(key = ServerKey("owk-test-wrong-key"))) }
        Scenario.field()
        Scenario.dictate()
        val state = Bubble.expectState("Problem", limit = Scenario.LONG_WAIT_MS)
        Bubble.control("Fix")
        Device.screenshot("et-key-rejected")
        assertEquals("Problem. The server rejected the key", state)
        assertTrue("the recording is kept", Device.recordings().isNotEmpty())
        Scenario.assertNotTyped()
    }

    /** Asks JobScheduler to run the app's WorkManager jobs now (they wait for a network and back off). */
    private fun runPendingJobs() {
        val jobs = Regex("JOB #u\\d+a\\d+/(\\d+): \\w+ ${Device.app.packageName}/androidx.work")
            .findAll(Device.shell("dumpsys jobscheduler")).map { it.groupValues[1] }.toSet()
        jobs.forEach { Device.shell("cmd jobscheduler run -f ${Device.app.packageName} $it") }
    }

    private companion object {
        /** On the server's host, but nothing listens there: the connection is refused at once. */
        val DEAD_PORT = ServerEndpoint("10.0.2.2", 8179)
    }
}
