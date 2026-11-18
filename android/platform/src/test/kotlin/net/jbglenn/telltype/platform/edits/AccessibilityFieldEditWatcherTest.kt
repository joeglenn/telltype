package net.jbglenn.telltype.platform.edits

import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.platform.service.AccessibilitySignal
import net.jbglenn.telltype.platform.testdoubles.FakeFieldAccess
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityFieldEditWatcherTest {
    private val signals = MutableSharedFlow<AccessibilitySignal>(extraBufferCapacity = 16)
    private val switches = mutableListOf<Boolean>()
    private val switch = TextChangeSwitch { switches += it }
    private val field = FakeFieldAccess(text = "Hello world", kind = FieldKind.MULTI_LINE, windowId = 7)

    private fun TestScope.watcher(resolved: FakeFieldAccess? = field) = AccessibilityFieldEditWatcher(
        resolve = { resolved },
        signals = signals,
        switch = switch,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
    )

    private suspend fun edit(text: String, windowId: Int = 7) {
        field.text = text
        signals.emit(AccessibilitySignal.TextChanged(windowId))
    }

    @Test
    fun `emits each new text of the field's window until the window passes, then switches events off`() = runTest {
        watcher().edits(Fixtures.fieldTarget(), 60.seconds).test {
            runCurrent()
            assertEquals(listOf(true), switches)
            edit("Hello word")
            assertEquals("Hello word", awaitItem())
            signals.emit(AccessibilitySignal.TextChanged(7))
            edit("Hello other window", windowId = 8)
            edit("Hello words")
            assertEquals("Hello words", awaitItem())
            advanceTimeBy(61.seconds)
            awaitComplete()
        }
        assertEquals(listOf(true, false), switches)
        assertFalse(switch.on)
    }

    @Test
    fun `ends when the field disappears`() = runTest {
        watcher().edits(Fixtures.fieldTarget(), 60.seconds).test {
            runCurrent()
            field.exists = false
            signals.emit(AccessibilitySignal.TextChanged(7))
            awaitComplete()
        }
        assertEquals(listOf(true, false), switches)
    }

    @Test
    fun `a cancelled watch switches the events off`() = runTest {
        watcher().edits(Fixtures.fieldTarget(), 60.seconds).test {
            runCurrent()
            assertTrue(switch.on)
            cancelAndIgnoreRemainingEvents()
        }
        runCurrent()
        assertFalse(switch.on)
    }

    @Test
    fun `incognito, protected and unknown fields are not watched`() = runTest {
        listOf(
            Fixtures.fieldTarget(incognito = true),
            Fixtures.fieldTarget(kind = FieldKind.PASSWORD),
            Fixtures.fieldTarget(kind = FieldKind.NUMERIC),
        ).forEach { target -> watcher().edits(target, 60.seconds).test { awaitComplete() } }
        watcher(resolved = null).edits(Fixtures.fieldTarget(), 60.seconds).test { awaitComplete() }

        assertEquals(emptyList<Boolean>(), switches)
    }

    @Test
    fun `two watches keep the events on until both end`() {
        switch.acquire()
        switch.acquire()
        switch.release()
        assertTrue(switch.on)
        switch.release()

        assertEquals(listOf(true, false), switches)
    }
}
