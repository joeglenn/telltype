package net.jbglenn.telltype.testing

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.StorageFull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AppSupportFakesTest {
    @Test
    fun `snooze holds the end time until resumed`() = runTest {
        val snooze = FakeBubbleSnooze()

        assertNull(snooze.snoozedUntil.value)
        snooze.snoozeUntil(Fixtures.Epoch)
        assertEquals(Fixtures.Epoch, snooze.snoozedUntil.value)
        snooze.resume()
        assertNull(snooze.snoozedUntil.value)
    }

    @Test
    fun `reporter, self-test and exporter succeed by default and can fail`() = runTest {
        val reporter = FakeMisrecognitionReporter()
        val selfTest = FakeSelfTest()
        val exporter = FakeDebugBundleExporter()

        assertEquals(Outcome.Ok(Unit), reporter.report(Fixtures.historyEntry()))
        assertEquals(true, (selfTest.run() as Outcome.Ok).value.passed)
        assertEquals(Outcome.Ok(Unit), exporter.export())

        reporter.reportCall.returns(Outcome.Err(KeyRejected))
        exporter.exportCall.returns(Outcome.Err(StorageFull))
        assertEquals(Outcome.Err(KeyRejected), reporter.report(Fixtures.historyEntry()))
        assertEquals(Outcome.Err(StorageFull), exporter.export())
        assertEquals(1, selfTest.runCall.calls.size)
    }

    @Test
    fun `reporter forgets one entry's reports or all of them, and can fail`() = runTest {
        val reporter = FakeMisrecognitionReporter()

        assertEquals(Outcome.Ok(Unit), reporter.forget(SessionId("a")))
        assertEquals(Outcome.Ok(Unit), reporter.forgetAll())
        reporter.forgetAllCall.returns(Outcome.Err(StorageFull))

        assertEquals(Outcome.Err(StorageFull), reporter.forgetAll())
        assertEquals(listOf(SessionId("a")), reporter.forgetCall.calls)
        assertEquals(2, reporter.forgetAllCall.calls.size)
    }
}
