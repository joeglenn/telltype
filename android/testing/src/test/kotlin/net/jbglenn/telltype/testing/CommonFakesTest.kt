package net.jbglenn.telltype.testing

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.debug
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class) // currentTime
class CommonFakesTest {
    @Test
    fun `scripted call records, waits on virtual time and answers queued results first`() = runTest {
        val call = ScriptedCall<Int, String> { "standing $it" }
        call.delay = 250.milliseconds
        call.returnsNext("first")

        assertEquals("first", call.answer(1))
        assertEquals("standing 2", call.answer(2))
        call.returns("fixed")
        assertEquals("fixed", call.answer(3))
        assertEquals(listOf(1, 2, 3), call.calls)
        assertEquals(750, currentTime)
    }

    @Test
    fun `every dispatcher of the test provider is the test dispatcher`() = runTest {
        val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))

        listOf(dispatchers.main, dispatchers.io, dispatchers.default, dispatchers.accessibility, dispatchers.audio)
            .forEach { assertSame(dispatchers.testDispatcher, it) }
        withContext(dispatchers.audio) { delay(1.seconds) }
        assertEquals(1_000, currentTime)
    }

    @Test
    fun `fixed clock moves only when advanced`() {
        val clock = FixedClock()

        assertEquals(Fixtures.Epoch, clock.now())
        clock.advanceBy(3.seconds)
        assertEquals(Fixtures.Epoch + 3.seconds, clock.now())
        assertEquals(3.seconds, clock.monotonic())
        assertThrows<IllegalArgumentException> { clock.advanceBy((-1).seconds) }
    }

    @Test
    fun `ids are sequential per kind`() {
        val ids = SequentialIdGenerator()

        assertEquals(SessionId("session-1"), ids.newSessionId())
        assertEquals(RecordingId("recording-1"), ids.newRecordingId())
        assertEquals(SessionId("session-2"), ids.newSessionId())
    }

    @Test
    fun `recording logger keeps enabled lines only`() {
        val logger = RecordingLogger(minimum = LogLevel.INFO)
        val tag = LogTag("Test")

        logger.debug(tag) { "hidden" }
        logger.info(tag) { "shown" }

        assertEquals(listOf(LogEntry(LogLevel.INFO, tag, "shown", null)), logger.entries)
    }
}
