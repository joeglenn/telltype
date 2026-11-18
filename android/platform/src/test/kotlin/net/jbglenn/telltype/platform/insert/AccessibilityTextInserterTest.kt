package net.jbglenn.telltype.platform.insert

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.InsertNotConfirmed
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.settings.InsertionSettings
import net.jbglenn.telltype.platform.testdoubles.FakeClipboardAccess
import net.jbglenn.telltype.platform.testdoubles.FakeFieldAccess
import net.jbglenn.telltype.platform.testdoubles.FakeFieldAccess.Behaviour
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccessibilityTextInserterTest {
    private val target = Fixtures.fieldTarget(handle = "field-1")
    private val clipboard = FakeClipboardAccess()
    private val settings = FakeSettingsRepository()
    private val logger = RecordingLogger()
    private val field =
        FakeFieldAccess(text = "Hello world", selection = TextRange(5, 5)).also { it.clipboard = clipboard }

    private fun TestScope.inserter(
        resolve: (FieldHandle) -> FakeFieldAccess? = { field.takeIf { _ -> it == target.handle } },
    ) = AccessibilityTextInserter(
        resolve = resolve,
        ladder = InsertionLadder(clipboard, LadderConfig(stepTimeout = 1.seconds, pollInterval = 25.milliseconds)),
        settings = settings,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        logger = logger,
        timeout = 5.seconds,
    )

    @Test
    fun `insert returns a receipt for the target, with the step and where the text sits`() = runTest {
        val receipt = inserter().insert(target, ",")

        val expected = InsertReceipt(target, ",", "", TextRange(5, 6), InsertedVia.INPUT_CONNECTION)
        assertEquals(Outcome.Ok(expected), receipt)
    }

    @Test
    fun `a field that adapts the text gets a receipt for the text as it landed, so Raw and Undo find it`() = runTest {
        field.text = "HELLO WORLD"
        field.inputConnection = Behaviour.ADAPTS

        val receipt = (inserter().insert(target, ", there") as Outcome.Ok).value

        assertEquals(InsertReceipt(target, " THERE", "", TextRange(5, 11), InsertedVia.INPUT_CONNECTION), receipt)
        field.inputConnection = Behaviour.LANDS
        val undone = inserter().replace(receipt, "")
        assertEquals("HELLO WORLD", field.text)
        assertEquals(TextRange(5, 5), (undone as Outcome.Ok).value.range)
    }

    @Test
    fun `an unknown handle is a field that is gone`() = runTest {
        assertEquals(Outcome.Err(FieldGone), inserter { null }.insert(target, ","))
    }

    @Test
    fun `never use the clipboard reaches the ladder`() = runTest {
        settings.update { it.copy(insertion = InsertionSettings(neverUseClipboard = true)) }
        field.inputConnection = Behaviour.NOT_AVAILABLE
        field.setTextAction = Behaviour.NOT_AVAILABLE

        assertEquals(Outcome.Err(InsertNotConfirmed), inserter().insert(target, ","))
        assertEquals(emptyList<String>(), clipboard.operations)
    }

    @Test
    fun `replace puts the raw text where the receipt says and records what it replaced`() = runTest {
        field.text = "Hello, world"
        val first = InsertReceipt(target, ",", "", TextRange(5, 6), InsertedVia.INPUT_CONNECTION)

        val raw = inserter().replace(first, " comma")

        val expected = InsertReceipt(target, " comma", ",", TextRange(5, 11), InsertedVia.INPUT_CONNECTION)
        assertEquals(Outcome.Ok(expected), raw)
        assertEquals("Hello comma world", field.text)
    }

    @Test
    fun `replace of a hand-off or a receipt without a range is refused`() = runTest {
        val handedOff = InsertReceipt(target, "text", "", null, InsertedVia.HANDOFF)
        val noRange = InsertReceipt(target, "text", "", null, InsertedVia.SET_TEXT)

        assertEquals(Outcome.Err(FieldChanged), inserter().replace(handedOff, ""))
        assertEquals(Outcome.Err(FieldChanged), inserter().replace(noRange, ""))
    }

    @Test
    fun `an insertion that outlasts the overall timeout is not confirmed`() = runTest {
        field.inputConnection = Behaviour.IGNORED
        field.setTextAction = Behaviour.IGNORED
        field.pasteAction = Behaviour.IGNORED
        val slow = AccessibilityTextInserter(
            resolve = { field },
            ladder = InsertionLadder(clipboard, LadderConfig(stepTimeout = 2.seconds)),
            settings = settings,
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            logger = logger,
            timeout = 3.seconds,
        )

        assertEquals(Outcome.Err(InsertNotConfirmed), slow.insert(target, ","))
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals("previous clip", clipboard.content)
    }

    @Test
    fun `the log names the step but never the text`() = runTest {
        inserter().insert(target, "secret words")

        val lines = logger.entries.map { it.message }
        assertEquals(1, lines.size)
        assertFalse(lines.single().contains("secret"))
    }
}
