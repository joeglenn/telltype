package net.jbglenn.telltype.domain

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Proves the JVM test toolchain for pure modules: JUnit Jupiter, kotlinx-coroutines-test, Turbine and the
 * shared [TestDispatcherProvider] from :testing, with delays on injected dispatchers taking virtual time.
 */
class TestToolchainTest {
    @OptIn(ExperimentalCoroutinesApi::class) // currentTime
    @Test
    fun `injected io dispatcher runs on virtual time`() = runTest {
        val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val ticks = flow {
            repeat(TICKS) { tick ->
                delay(TICK_MILLIS)
                emit(tick)
            }
        }.flowOn(dispatchers.io)

        ticks.test {
            repeat(TICKS) { tick -> assertEquals(tick, awaitItem()) }
            awaitComplete()
        }
        assertEquals(TICKS * TICK_MILLIS, currentTime)
    }

    @Test
    fun `turbine reports an error thrown by the flow`() = runTest {
        val failing = flow<Int> { error("boom") }

        failing.test {
            assertEquals("boom", awaitError().message)
        }
    }

    private companion object {
        const val TICKS = 3
        const val TICK_MILLIS = 60_000L
    }
}
