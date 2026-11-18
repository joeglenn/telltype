package net.jbglenn.telltype.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import net.jbglenn.telltype.core.common.DispatcherProvider

/**
 * Routes every dispatcher, including the single-thread accessibility and audio ones, to one [TestDispatcher], so
 * code under test runs on the virtual time of `runTest`. Pass `testScheduler` from the test scope:
 * `TestDispatcherProvider(StandardTestDispatcher(testScheduler))`.
 */
public class TestDispatcherProvider(public val testDispatcher: TestDispatcher = StandardTestDispatcher()) :
    DispatcherProvider {
    override val main: CoroutineDispatcher get() = testDispatcher
    override val io: CoroutineDispatcher get() = testDispatcher
    override val default: CoroutineDispatcher get() = testDispatcher
    override val accessibility: CoroutineDispatcher get() = testDispatcher
    override val audio: CoroutineDispatcher get() = testDispatcher
}
