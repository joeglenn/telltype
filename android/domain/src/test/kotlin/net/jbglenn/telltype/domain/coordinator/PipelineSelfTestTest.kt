package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.domain.SharedAssets
import net.jbglenn.telltype.domain.port.SelfTestReport
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FakeSpeechToTextEngine
import net.jbglenn.telltype.testing.FakeTextCleaner
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.SequentialIdGenerator
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Diagnostics self-test against the pipeline ports. */
class PipelineSelfTestTest {
    private val speech = FakeSpeechToTextEngine()
    private val cleaner = FakeTextCleaner()

    private fun TestScope.selfTest(expected: String = "hello, world"): PipelineSelfTest = PipelineSelfTest(
        sample = SelfTestSample(Fixtures.audioClip(), expected),
        speech = speech,
        cleaner = cleaner,
        settings = FakeSettingsRepository(configuredSettings()),
        environment = CoordinatorEnvironment(
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            clock = SchedulerClock(testScheduler),
            ids = SequentialIdGenerator(),
            logger = RecordingLogger(),
            guardRules = SharedAssets.rules,
        ),
    )

    @Test
    fun `a matching result passes with its timings`() = runTest {
        speech.transcribeCall.delay = 300.milliseconds
        speech.transcribeCall.returns(
            Outcome.Ok(Transcript("hello world", 3_000.milliseconds, listOf(ServerTiming("stt", 200.milliseconds)))),
        )
        cleaner.cleanCall.delay = 100.milliseconds
        val report = (selfTest().run() as Outcome.Ok<SelfTestReport>).value
        assertTrue(report.passed)
        assertEquals(300.milliseconds, report.timings.transcription)
        assertEquals(100.milliseconds, report.timings.cleanup)
        assertEquals(400.milliseconds, report.timings.endToEnd)
        assertEquals(listOf(ServerTiming("stt", 200.milliseconds)), report.timings.server)
        assertEquals("hello world", cleaner.cleanCall.calls.single().transcript)
    }

    @Test
    fun `a different result fails, and stage errors are reported`() = runTest {
        val report = (selfTest(expected = "goodbye").run() as Outcome.Ok<SelfTestReport>).value
        assertFalse(report.passed)
        speech.transcribeCall.returns(Outcome.Err(KeyRejected))
        assertEquals(Outcome.Err(KeyRejected), selfTest().run())
        speech.transcribeCall.returns(Outcome.Ok(Fixtures.transcript()))
        cleaner.cleanCall.delay = 10_000.milliseconds
        assertEquals(Outcome.Err(CleanupTimedOut), selfTest().run())
    }

    @Test
    fun `the sample hides its expected text`() {
        assertFalse("secret phrase" in SelfTestSample(Fixtures.audioClip(), "secret phrase").toString())
    }
}
