package net.jbglenn.telltype.support

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deleting History entries also removes the \"That was wrong\" reports made from them, which hold their text. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FileMisrecognitionReporterTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val file = File(context.filesDir, "reports/misrecognitions.jsonl")

    private fun TestScope.reporter() =
        FileMisrecognitionReporter(context, TestDispatcherProvider(StandardTestDispatcher(testScheduler)))

    @After
    fun removeFile() {
        file.delete()
    }

    @Test
    fun `forgetting one entry removes only its reports`() = runTest {
        val reporter = reporter()
        reporter.report(Fixtures.historyEntry(id = "a", finalText = "Alpha words.", rawTranscript = "alpha words"))
        reporter.report(Fixtures.historyEntry(id = "b", finalText = "Bravo words.", rawTranscript = "bravo words"))
        reporter.report(Fixtures.historyEntry(id = "a", finalText = "Alpha words.", rawTranscript = "alpha words"))

        assertEquals(Outcome.Ok(Unit), reporter.forget(SessionId("a")))

        val text = file.readText()
        assertFalse(text.contains("lpha", ignoreCase = true))
        assertTrue(text.contains("Bravo words."))
        assertEquals(1, file.readLines().size)
        assertFalse(File(file.parentFile, "misrecognitions.jsonl.next").exists())
    }

    @Test
    fun `forgetting the last entry's reports deletes the file`() = runTest {
        val reporter = reporter()
        reporter.report(Fixtures.historyEntry(id = "a"))

        assertEquals(Outcome.Ok(Unit), reporter.forget(SessionId("a")))

        assertFalse(file.exists())
    }

    @Test
    fun `forgetting all deletes the file, and nothing to forget is fine`() = runTest {
        val reporter = reporter()
        reporter.report(Fixtures.historyEntry(id = "a"))
        reporter.report(Fixtures.historyEntry(id = "b"))

        assertEquals(Outcome.Ok(Unit), reporter.forgetAll())
        assertFalse(file.exists())
        assertEquals(Outcome.Ok(Unit), reporter.forgetAll())
        assertEquals(Outcome.Ok(Unit), reporter.forget(SessionId("a")))
        assertEquals(null, reporter.reportFile())
    }
}
