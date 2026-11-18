package net.jbglenn.telltype.ui.screens

import java.time.ZoneId
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.screens.common.LocalTime
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Runs `viewModelScope` on a test dispatcher, so ViewModel tests use virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/** A fixed moment in a fixed zone. */
class FixedLocalTime(
    private val instant: Instant = Fixtures.Epoch,
    private val zoneId: ZoneId = ZoneId.of("Europe/Berlin"),
) : LocalTime {
    override fun now(): Instant = instant

    override fun zone(): ZoneId = zoneId
}

/** Keeps a `WhileSubscribed` state flowing for the rest of the test, starting at once. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.keepCollecting(flow: Flow<*>) {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
}

/** Collects one-off effects into a list for the rest of the test. */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.collectInto(flow: Flow<T>): List<T> {
    val received = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { received += it } }
    return received
}
