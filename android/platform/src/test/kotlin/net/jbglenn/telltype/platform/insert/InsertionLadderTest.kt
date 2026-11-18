package net.jbglenn.telltype.platform.insert

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.FieldProtected
import net.jbglenn.telltype.core.model.error.InsertNotConfirmed
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.platform.testdoubles.FakeClipboardAccess
import net.jbglenn.telltype.platform.testdoubles.FakeFieldAccess
import net.jbglenn.telltype.platform.testdoubles.FakeFieldAccess.Behaviour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InsertionLadderTest {
    private val clipboard = FakeClipboardAccess()
    private val config = LadderConfig(stepTimeout = 1.seconds, pollInterval = 25.milliseconds)
    private val ladder = InsertionLadder(clipboard, config)

    private fun field(text: String? = "Hello world", selection: TextRange? = TextRange(5, 5)) =
        FakeFieldAccess(text = text, selection = selection).also { it.clipboard = clipboard }

    private fun insert(text: String, allowClipboard: Boolean = true) =
        InsertRequest(text, range = null, expected = null, requireFocus = true, allowClipboard = allowClipboard)

    @Test
    fun `step 1 types at the cursor and names the input connection`() = runTest {
        val field = field()

        val result = ladder.run(field, insert(" there"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.INPUT_CONNECTION, TextRange(5, 11), "")), result)
        assertEquals("Hello there world", field.text)
        assertEquals(listOf("commit"), field.calls)
    }

    @Test
    fun `step 1 replaces the selection and reports what it replaced`() = runTest {
        val field = field(selection = TextRange(6, 11))

        val result = ladder.run(field, insert("Oslo"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.INPUT_CONNECTION, TextRange(6, 10), "world")), result)
        assertEquals("Hello Oslo", field.text)
    }

    @Test
    fun `an asynchronous commit is confirmed when it lands within the step timeout`() = runTest {
        val field = field().apply { readsBeforeLanding = 10 }

        val result = ladder.run(field, insert("!"))

        assertEquals(InsertedVia.INPUT_CONNECTION, (result as Outcome.Ok).value.via)
        assertEquals(250L, currentTime)
    }

    @Test
    fun `an ignored commit times out and the set-text step lands instead`() = runTest {
        val field = field().apply { inputConnection = Behaviour.IGNORED }

        val result = ladder.run(field, insert(","))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.SET_TEXT, TextRange(5, 6), "")), result)
        assertEquals("Hello, world", field.text)
        assertEquals(TextRange(6, 6), field.selection)
        assertTrue("the input connection step waited its full timeout", currentTime >= 1_000L)
    }

    @Test
    fun `a commit that changed the text wrongly is repaired by set-text and never pasted`() = runTest {
        val field = field().apply { inputConnection = Behaviour.MANGLES }

        val result = ladder.run(field, insert(","))

        assertEquals(InsertedVia.SET_TEXT, (result as Outcome.Ok).value.via)
        assertEquals("Hello, world", field.text)
        assertEquals(listOf("commit", "setText"), field.calls)
    }

    @Test
    fun `without an input connection the ladder starts at set-text`() = runTest {
        val field = field().apply { inputConnection = Behaviour.NOT_AVAILABLE }

        val result = ladder.run(field, insert("!"))

        assertEquals(InsertedVia.SET_TEXT, (result as Outcome.Ok).value.via)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a field showing its placeholder skips set-text and is pasted into, restoring the clipboard`() = runTest {
        val field = field(text = "Type a message", selection = TextRange(0, 0)).apply {
            showingHint = true
            inputConnection = Behaviour.NOT_AVAILABLE
        }

        val result = ladder.run(field, insert("Hi"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.PASTE, TextRange(0, 2), "")), result)
        assertEquals("Hi", field.text)
        assertEquals(listOf("commit", "paste"), field.calls)
        assertEquals(listOf("save", "put", "restore"), clipboard.operations)
        assertEquals("previous clip", clipboard.content)
    }

    @Test
    fun `a paste that is not confirmed still restores the clipboard`() = runTest {
        val field = field().apply {
            inputConnection = Behaviour.NOT_AVAILABLE
            setTextAction = Behaviour.NOT_AVAILABLE
            pasteAction = Behaviour.IGNORED
        }

        val result = ladder.run(field, insert("!"))

        assertEquals(Outcome.Err(InsertNotConfirmed), result)
        assertEquals("previous clip", clipboard.content)
        assertEquals(listOf("save", "put", "restore"), clipboard.operations)
    }

    @Test
    fun `never use the clipboard stops after set-text`() = runTest {
        val field = field().apply {
            inputConnection = Behaviour.NOT_AVAILABLE
            setTextAction = Behaviour.NOT_AVAILABLE
        }

        val result = ladder.run(field, insert("!", allowClipboard = false))

        assertEquals(Outcome.Err(InsertNotConfirmed), result)
        assertEquals(emptyList<String>(), clipboard.operations)
        assertEquals("Hello world", field.text)
    }

    @Test
    fun `a clipboard that refuses the clip is not pasted from`() = runTest {
        clipboard.refusesPut = true
        val field = field().apply {
            inputConnection = Behaviour.NOT_AVAILABLE
            setTextAction = Behaviour.NOT_AVAILABLE
        }

        assertEquals(Outcome.Err(InsertNotConfirmed), ladder.run(field, insert("!")))
        assertEquals(listOf("commit", "setText"), field.calls)
    }

    @Test
    fun `a field that is gone is reported as gone`() = runTest {
        val field = field().apply { exists = false }

        assertEquals(Outcome.Err(FieldGone), ladder.run(field, insert("!")))
        assertEquals(emptyList<String>(), field.calls)
    }

    @Test
    fun `a field that disappears while a step is confirmed is reported as gone`() = runTest {
        val field = field().apply { inputConnection = Behaviour.IGNORED }
        val vanishing = object : net.jbglenn.telltype.platform.field.FieldAccess by field {
            override fun commitText(range: TextRange?, text: String): Boolean =
                field.commitText(range, text).also { field.exists = false }
        }

        assertEquals(Outcome.Err(FieldGone), ladder.run(vanishing, insert("!")))
    }

    @Test
    fun `a new insertion into a field without focus is a changed field and types nothing`() = runTest {
        val field = field().apply { focused = false }

        assertEquals(Outcome.Err(FieldChanged), ladder.run(field, insert("!")))
        assertEquals(emptyList<String>(), field.calls)
    }

    @Test
    fun `a password or numeric field is protected`() = runTest {
        for (kind in listOf(FieldKind.PASSWORD, FieldKind.NUMERIC)) {
            val field = field().apply { this.kind = kind }

            assertEquals(Outcome.Err(FieldProtected), ladder.run(field, insert("1234")))
            assertEquals(emptyList<String>(), field.calls)
        }
    }

    @Test
    fun `replace swaps exactly the inserted text, also in a field without focus`() = runTest {
        val field = field(text = "Hello there world", selection = TextRange(17, 17)).apply { focused = false }
        val request = InsertRequest("Raw", TextRange(5, 11), expected = " there", requireFocus = false, true)

        val result = ladder.run(field, request)

        assertEquals(Outcome.Ok(Inserted(InsertedVia.SET_TEXT, TextRange(5, 8), " there")), result)
        assertEquals("HelloRaw world", field.text)
        assertEquals(listOf("setText"), field.calls)
    }

    @Test
    fun `undo with empty text deletes the insertion and does not paste`() = runTest {
        val field = field(text = "Hello there world", selection = TextRange(11, 11)).apply {
            inputConnection = Behaviour.NOT_AVAILABLE
            setTextAction = Behaviour.NOT_AVAILABLE
        }
        val request = InsertRequest("", TextRange(5, 11), expected = " there", requireFocus = false, true)

        assertEquals(Outcome.Err(InsertNotConfirmed), ladder.run(field, request))
        assertEquals(emptyList<String>(), clipboard.operations)

        field.setTextAction = Behaviour.LANDS
        assertEquals(InsertedVia.SET_TEXT, (ladder.run(field, request) as Outcome.Ok).value.via)
        assertEquals("Hello world", field.text)
    }

    @Test
    fun `replace refuses when the user edited the inserted text`() = runTest {
        val field = field(text = "Hello thera world")
        val request = InsertRequest("Raw", TextRange(5, 11), expected = " there", requireFocus = false, true)

        assertEquals(Outcome.Err(FieldChanged), ladder.run(field, request))
        assertEquals(emptyList<String>(), field.calls)
    }

    @Test
    fun `a field with neither readable text nor a selection is not typed into`() = runTest {
        val field = field(text = null, selection = null)

        assertEquals(Outcome.Err(InsertNotConfirmed), ladder.run(field, insert("!")))
        assertEquals(emptyList<String>(), field.calls)
    }

    @Test
    fun `unreadable text is confirmed by the cursor moving past the insertion`() = runTest {
        val field = field(text = null, selection = TextRange(3, 3))

        val result = ladder.run(field, insert("abc"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.INPUT_CONNECTION, TextRange(3, 6), "")), result)
    }

    @Test
    fun `with unreadable text an unconfirmed step is never followed by a paste`() = runTest {
        val field = field(text = null, selection = TextRange(3, 3)).apply { inputConnection = Behaviour.IGNORED }

        assertEquals(Outcome.Err(InsertNotConfirmed), ladder.run(field, insert("abc")))
        assertEquals(listOf("commit"), field.calls)
        assertEquals(emptyList<String>(), clipboard.operations)
    }

    @Test
    fun `without a selection the insertion is located in the new text`() = runTest {
        val field = field(text = "Hello world", selection = null)
        field.inputConnection = Behaviour.NOT_AVAILABLE
        val located = object : net.jbglenn.telltype.platform.field.FieldAccess by field {
            override fun paste(range: TextRange?): Boolean = field.paste(TextRange(5, 5))
        }

        val result = ladder.run(located, insert(","))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.PASTE, TextRange(5, 6), "")), result)
    }

    @Test
    fun `restricting the steps runs only those`() = runTest {
        val pasteOnly = InsertionLadder(clipboard, config.copy(steps = listOf(LadderStep.PASTE)))
        val field = field()

        val result = pasteOnly.run(field, insert("!"))

        assertEquals(InsertedVia.PASTE, (result as Outcome.Ok).value.via)
        assertEquals(listOf("paste"), field.calls)
    }

    @Test
    fun `a field that adapts the text (capitals, filtered commas) counts as typed, not handed off`() = runTest {
        val field = field(text = "HELLO WORLD").apply {
            inputConnection = Behaviour.ADAPTS
            setTextAction = Behaviour.ADAPTS
        }

        val result = ladder.run(field, insert(", there"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.INPUT_CONNECTION, TextRange(5, 11), "", " THERE")), result)
        assertEquals("HELLO THERE WORLD", field.text)
        assertEquals("accepted after the first step's timeout", listOf("commit"), field.calls)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun `an adapting field reached by set-text is accepted there`() = runTest {
        val field = field(text = "", selection = TextRange(0, 0)).apply {
            inputConnection = Behaviour.IGNORED
            setTextAction = Behaviour.ADAPTS
        }

        val result = ladder.run(field, insert("Hi, Oslo"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.SET_TEXT, TextRange(0, 7), "", "HI OSLO")), result)
        assertEquals(listOf("commit", "setText"), field.calls)
    }

    @Test
    fun `a field holding something that is not our text is not taken for an adaptation`() = runTest {
        val field = field().apply {
            inputConnection = Behaviour.MANGLES
            setTextAction = Behaviour.MANGLES
        }

        val result = ladder.run(field, insert("!"))

        assertEquals(Outcome.Err(InsertNotConfirmed), result)
    }

    @Test
    fun `a deaf field goes through every step and ends unconfirmed`() = runTest {
        val field = field().apply {
            inputConnection = Behaviour.IGNORED
            setTextAction = Behaviour.IGNORED
            pasteAction = Behaviour.IGNORED
        }

        val result = ladder.run(field, insert("!"))

        assertEquals(Outcome.Err(InsertNotConfirmed), result)
        assertEquals(listOf("commit", "setText", "paste"), field.calls)
        assertEquals("previous clip", clipboard.content)
    }

    @Test
    fun `a field that takes text only through paste is pasted into`() = runTest {
        val field = field().apply {
            inputConnection = Behaviour.IGNORED
            setTextAction = Behaviour.IGNORED
        }

        val result = ladder.run(field, insert(" there"))

        assertEquals(Outcome.Ok(Inserted(InsertedVia.PASTE, TextRange(5, 11), "")), result)
        assertEquals("Hello there world", field.text)
    }
}
