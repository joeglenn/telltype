package net.jbglenn.telltype.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreBubbleSnoozeTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logger = RecordingLogger()
    private val file: File get() = File(folder.root, "bubble_snooze.preferences_pb")
    private val storedKey = longPreferencesKey("bubble_snoozed_until_epoch_ms")

    /** Wall and monotonic time that follow the test scheduler's virtual time; [jump] moves the wall clock alone. */
    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : Clock {
        var jump: Duration = Duration.ZERO

        override fun now(): Instant = Fixtures.Epoch + scheduler.currentTime.milliseconds + jump

        override fun monotonic(): Duration = scheduler.currentTime.milliseconds
    }

    /** A snooze on [file] whose DataStore lives until [stop], like one process lifetime. */
    private inner class Opened(scope: TestScope, val clock: SchedulerClock = SchedulerClock(scope.testScheduler)) {
        private val job = Job()
        private val lifetime = CoroutineScope(scope.coroutineContext + job)
        val store = preferencesStore(file, lifetime, logger)
        val snooze = DataStoreBubbleSnooze(store, clock, lifetime, scope.testDispatchers(), logger)

        suspend fun stored(): Long? = store.data.first()[storedKey]

        suspend fun stop() = job.cancelAndJoin()
    }

    private fun TestScope.at(offset: Duration): Instant =
        Fixtures.Epoch + testScheduler.currentTime.milliseconds + offset

    @Test
    fun `nothing stored is not snoozed`() = runTest {
        val opened = Opened(this)
        runCurrent()

        assertNull(opened.snooze.snoozedUntil.value)
        opened.stop()
    }

    @Test
    fun `a snooze is published at once, stored, and ends by itself at its time`() = runTest {
        val opened = Opened(this)
        val until = at(1.hours)

        opened.snooze.snoozeUntil(until)

        assertEquals(until, opened.snooze.snoozedUntil.value)
        assertEquals(until.toEpochMilliseconds(), opened.stored())
        advanceTimeBy(1.hours - 1.milliseconds)
        runCurrent()
        assertEquals("still snoozed just before its time", until, opened.snooze.snoozedUntil.value)
        advanceTimeBy(1.milliseconds)
        runCurrent()
        assertNull("over at its time", opened.snooze.snoozedUntil.value)
        assertNull("and removed from the file", opened.stored())
        opened.stop()
    }

    @Test
    fun `a snooze survives a restart and still ends at its time`() = runTest {
        val first = Opened(this)
        val until = at(30.minutes)
        first.snooze.snoozeUntil(until)
        first.stop()

        val second = Opened(this)
        runCurrent()

        assertEquals(until, second.snooze.snoozedUntil.value)
        advanceTimeBy(30.minutes)
        runCurrent()
        assertNull(second.snooze.snoozedUntil.value)
        second.stop()
    }

    @Test
    fun `a snooze that ended while the app was not running is gone on restart`() = runTest {
        val first = Opened(this)
        first.snooze.snoozeUntil(at(10.minutes))
        first.stop()
        advanceTimeBy(20.minutes)

        val second = Opened(this)
        runCurrent()

        assertNull(second.snooze.snoozedUntil.value)
        assertNull("the stale value is removed", second.stored())
        second.stop()
    }

    @Test
    fun `resume ends the snooze now and for good`() = runTest {
        val first = Opened(this)
        first.snooze.snoozeUntil(at(1.hours))

        first.snooze.resume()

        assertNull(first.snooze.snoozedUntil.value)
        assertNull(first.stored())
        first.stop()
        val second = Opened(this)
        runCurrent()
        assertNull(second.snooze.snoozedUntil.value)
        second.stop()
    }

    @Test
    fun `a new snooze replaces the earlier one and the earlier time no longer ends it`() = runTest {
        val opened = Opened(this)
        val later = at(30.minutes)
        opened.snooze.snoozeUntil(at(10.minutes))
        opened.snooze.snoozeUntil(later)

        advanceTimeBy(10.minutes + 1.seconds)
        runCurrent()
        assertEquals(later, opened.snooze.snoozedUntil.value)
        advanceTimeBy(20.minutes)
        runCurrent()
        assertNull(opened.snooze.snoozedUntil.value)
        opened.stop()
    }

    @Test
    fun `a time that is not in the future is no snooze and ends a running one`() = runTest {
        val opened = Opened(this)
        opened.snooze.snoozeUntil(at(1.hours))

        opened.snooze.snoozeUntil(at(Duration.ZERO))

        assertNull(opened.snooze.snoozedUntil.value)
        assertNull(opened.stored())
        opened.stop()
    }

    @Test
    fun `a wall clock moved past the end finishes the snooze within the recheck interval`() = runTest {
        val opened = Opened(this)
        opened.snooze.snoozeUntil(at(1.hours))

        opened.clock.jump = 2.hours
        advanceTimeBy(15.seconds)
        runCurrent()

        assertNull(opened.snooze.snoozedUntil.value)
        opened.stop()
    }

    @Test
    fun `storage failures keep the snooze in memory, are logged and never thrown`() = runTest {
        val until = at(1.hours)
        val snooze =
            DataStoreBubbleSnooze(
                BrokenStore,
                SchedulerClock(testScheduler),
                backgroundScope,
                testDispatchers(),
                logger,
            )

        snooze.snoozeUntil(until)
        assertEquals(until, snooze.snoozedUntil.value)
        snooze.resume()
        assertNull(snooze.snoozedUntil.value)

        val errors = logger.entries.filter { it.level == LogLevel.ERROR }
        assertTrue("read and both writes logged: $errors", errors.size >= 3)
        assertTrue(errors.all { it.throwable is IOException })
    }

    /** A store whose disk is gone: every read and write fails with an I/O error. */
    private object BrokenStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("synthetic: storage unavailable") }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw IOException("synthetic: storage unavailable")
    }
}
