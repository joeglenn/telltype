package net.jbglenn.telltype.platform.context

import java.util.concurrent.Executor
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.platform.field.FieldAccess
import net.jbglenn.telltype.platform.testdoubles.FakeFieldAccess
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityFieldContextReaderTest {
    private fun TestScope.reader(resolve: (FieldHandle) -> FieldAccess?) = AccessibilityFieldContextReader(
        resolve,
        TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        backgroundScope,
        timeout = 300.milliseconds,
    )

    @Test
    fun `reads the context of a resolved field`() = runTest {
        val field = FakeFieldAccess(text = "Hello world", selection = TextRange(5, 5), kind = FieldKind.MULTI_LINE)

        val context = reader { field }.read(Fixtures.fieldTarget(), charsBefore = 5, charsAfter = 3)

        assertEquals(FieldContext("Hello", " wo", ""), context)
    }

    @Test
    fun `incognito and protected targets are never resolved`() = runTest {
        var resolved = 0
        val reader = reader { FakeFieldAccess(text = "secret").also { resolved++ } }

        assertEquals(FieldContext.Empty, reader.read(Fixtures.fieldTarget(incognito = true), 10, 10))
        assertEquals(FieldContext.Empty, reader.read(Fixtures.fieldTarget(kind = FieldKind.PASSWORD), 10, 10))
        assertEquals(FieldContext.Empty, reader.read(Fixtures.fieldTarget(kind = FieldKind.NUMERIC), 10, 10))
        assertEquals(0, resolved)
    }

    @Test
    fun `a field that became a password field is not read`() = runTest {
        val field = FakeFieldAccess(text = "hunter2", selection = TextRange(7, 7), kind = FieldKind.PASSWORD)

        assertEquals(FieldContext.Empty, reader { field }.read(Fixtures.fieldTarget(), 10, 10))
    }

    @Test
    fun `an unknown handle or a vanished field gives nothing`() = runTest {
        val gone = FakeFieldAccess().apply { exists = false }

        assertEquals(FieldContext.Empty, reader { null }.read(Fixtures.fieldTarget(), 1, 1))
        assertEquals(FieldContext.Empty, reader { gone }.read(Fixtures.fieldTarget(), 1, 1))
    }

    @Test
    fun `a read stuck on the accessibility thread gives nothing after the timeout`() = runTest {
        // An accessibility thread that never gets to run the read, like one blocked in a cross-process call.
        val stuck = Executor { }.asCoroutineDispatcher()
        val dispatchers = object : DispatcherProvider by TestDispatcherProvider(StandardTestDispatcher(testScheduler)) {
            override val accessibility: CoroutineDispatcher = stuck
        }
        val reader = AccessibilityFieldContextReader(
            { FakeFieldAccess(text = "Hello", selection = TextRange(5, 5)) },
            dispatchers,
            backgroundScope,
            timeout = 300.milliseconds,
        )

        assertEquals(FieldContext.Empty, reader.read(Fixtures.fieldTarget(), 10, 10))
        assertEquals(300L, testScheduler.currentTime)
    }
}
