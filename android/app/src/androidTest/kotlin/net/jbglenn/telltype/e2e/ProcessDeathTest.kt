package net.jbglenn.telltype.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Error-table row "unexpected crash" (docs/PLAN.md sections 9 and 10): the app's process dies while listening and
 * while uploading, and the next start recovers the recording. The tests run in the app's own process, so each case
 * takes two `am instrument` runs (android/scripts/connected-tests.sh does both, on a cleared app):
 *
 * 1. [dieWhileListening] or [dieWhileUploading]: a dictation into the test host, then an uncaught exception on a
 *    thread of the process (the crash switch; the app's crash reporter writes its report, then the process dies).
 *    Uploading goes to the stub gate on port [SLOW_PORT] (`stub_gate.py --slow`), which holds the transcription.
 * 2. [recoverAfterDeath]: the next start (the service connects again): the recording is queued, one "Recovered"
 *    notice, the background job transcribes it on the server and offers the text in a notification; nothing is typed
 *    into the field that was focused; History holds the text (the session died before writing its entry, so the
 *    background job writes it, A7h); the crash report holds none of the dictated text.
 *
 * Every method runs only when the instrumentation argument `owkProcessDeath` names its phase, so a plain run of the
 * package skips them.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Test
    fun dieWhileListening() {
        assumePhase("listening")
        arrange(Arrange.GATE)
        Bubble.startListening()
        SystemClock.sleep(Scenario.SPEAK_MS)
        dieIn("Listening")
    }

    @Test
    fun dieWhileUploading() {
        assumePhase("uploading")
        arrange(ServerEndpoint("10.0.2.2", SLOW_PORT))
        Scenario.dictate()
        SystemClock.sleep(UPLOAD_STARTED_MS)
        dieIn("Transcribing")
    }

    @Test
    fun recoverAfterDeath() {
        assumePhase("recover")
        Arrange.configuredApp()
        Arrange.address(Arrange.GATE)
        Device.enableService()
        Scenario.field()
        val recovered = awaitNotifications("Recovered dictations")
        val transcribed = awaitNotifications("Transcribed later")
        Device.screenshot("et-process-death-recovered")
        val text = transcribed.firstOrNull()?.text.orEmpty()
        val report = crashReports().lastOrNull()
        Log.i(
            TAG,
            "after death: recovered $recovered, transcribed ${text.length} chars, " +
                "queue ${Arrange.pendingJobs().size}, crash report ${report?.name} (${report?.length()} bytes), " +
                "History '${Arrange.latestHistory()?.id}'",
        )
        assertEquals("one notice", 1, recovered.size)
        assertEquals(
            "Recovered 1 dictation. It will be transcribed when the server is reachable.",
            recovered.single().text,
        )
        assertEquals("one result", 1, transcribed.size)
        assertTrue("the background job transcribed words", text.isNotBlank())
        val entry = Arrange.latestHistory()
        assertTrue("the background job saved the words to History (A7h)", entry?.finalText?.isNotBlank() == true)
        assertEquals("offered, never typed", InsertedVia.NONE, entry?.insertedVia)
        assertNull("History records no error", entry?.errorCode)
        assertTrue("the queue is empty again", Scenario.eventually { Arrange.pendingJobs().isEmpty() })
        assertEquals("no recording left", emptyList<String>(), Device.awaitNoRecordings())
        Scenario.assertNotTyped()
        checkNotNull(report) { "a crash report was written" }
        val content = report.readText()
        assertTrue("the report is the crash switch's", content.contains(CRASH_MESSAGE))
        (DICTATED + text.split(' ').filter { it.length >= MIN_WORD }).forEach { word ->
            assertFalse("the crash report holds dictated text", content.contains(word, ignoreCase = true))
        }
    }

    private fun assumePhase(phase: String) = assumeTrue(
        "needs -e $PHASE_ARGUMENT $phase",
        InstrumentationRegistry.getArguments().getString(PHASE_ARGUMENT) == phase,
    )

    private fun arrange(endpoint: ServerEndpoint) {
        Arrange.configuredApp()
        Arrange.address(endpoint)
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(false)
        Bubble.faults()
        assertEquals("start without recordings", emptyList<String>(), Device.awaitNoRecordings())
        assertTrue("start with an empty queue", Arrange.pendingJobs().isEmpty())
        Scenario.field()
    }

    /** Checks the bubble is in [state], then throws on a new thread: the process dies like on any uncaught crash. */
    private fun dieIn(state: String) {
        val now = Bubble.state()
        Log.i(TAG, "crash switch: bubble '$now', recordings ${Device.recordings()}")
        assertTrue("the bubble is in $state when the process dies, not '$now'", now?.startsWith(state) == true)
        Thread({ throw IllegalStateException(CRASH_MESSAGE) }, "owk-crash-switch").start()
        SystemClock.sleep(DEATH_WAIT_MS)
        error("the process outlived the crash switch")
    }

    private fun crashReports(): List<File> =
        File(Device.app.filesDir, "crash").listFiles()?.sortedBy(File::getName).orEmpty()

    /** The app's notifications titled [title], once at least one shows (from `dumpsys notification`). */
    private fun awaitNotifications(title: String): List<Shown> {
        Scenario.eventually(Scenario.LONG_WAIT_MS) { notifications().any { it.title == title } }
        return notifications().filter { it.title == title }
    }

    private fun notifications(): List<Shown> = Device.shell("dumpsys notification --noredact")
        .split("NotificationRecord(")
        .filter { it.contains("pkg=${Device.app.packageName}") }
        .mapNotNull { record ->
            val title = EXTRA_TITLE.find(record)?.groupValues?.get(1) ?: return@mapNotNull null
            Shown(title, EXTRA_TEXT.find(record)?.groupValues?.get(1))
        }

    /** One notification: its title and text. */
    private data class Shown(val title: String, val text: String?)

    private companion object {
        const val PHASE_ARGUMENT = "owkProcessDeath"
        const val CRASH_MESSAGE = "A7g crash switch: the process dies here"
        const val SLOW_PORT = 8176
        const val UPLOAD_STARTED_MS = 2_000L
        const val DEATH_WAIT_MS = 30_000L
        const val MIN_WORD = 5
        val EXTRA_TITLE = Regex("""android\.title=String \((.*)\)""")
        val EXTRA_TEXT = Regex("""android\.text=String \((.*)\)""")

        /** Words of the clip; none may reach the crash report. */
        val DICTATED = listOf("Phoebe", "portrait", "turning away her eyes", "wish to see")
    }
}
