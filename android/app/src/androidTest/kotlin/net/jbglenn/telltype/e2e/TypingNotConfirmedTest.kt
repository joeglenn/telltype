package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Error-table row "typing not confirmed" and its neighbours: the host's deaf field takes the focus but keeps no text
 * from the input connection, set-text or paste, so the typing ladder moves through every step and ends in the
 * hand-off with Paste; a paste-only field is reached by the paste step; a field that adapts the text (capitals,
 * commas filtered out) counts as typed and is not handed off as well; a field with a length limit that keeps only
 * the first 40 characters is not typed and ends in the hand-off (A7h).
 */
@RunWith(AndroidJUnit4::class)
class TypingNotConfirmedTest {
    @get:Rule
    val shots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
        Bubble.faults()
    }

    @After
    fun tearDown() {
        Bubble.fakeEngines(false)
    }

    @Test
    fun theLadderEndsInThePasteHandOff() {
        Scenario.field("deaf")
        Scenario.dictate()
        val paste = Bubble.control("Paste", Scenario.LONG_WAIT_MS)
        Device.screenshot("et-typing-not-confirmed-paste")
        val entry = Arrange.latestHistory()
        Log.i(TAG, "typing not confirmed: Paste chip at $paste, bubble '${Bubble.state()}', via ${entry?.insertedVia}")
        assertEquals("handed off, not typed", InsertedVia.HANDOFF, entry?.insertedVia)
        assertTrue("the field stayed empty", Scenario.fieldText().isEmpty())
    }

    @Test
    fun aPasteOnlyFieldIsReachedByThePasteStep() {
        Scenario.field("paste_only")
        val entry = dictateIntoHistory()
        assertTrue("the text was pasted", Scenario.awaitFieldText(Scenario.PHOEBE))
        Device.screenshot("et-typing-paste-only")
        Log.i(TAG, "paste-only field: via ${entry?.insertedVia}, bubble '${Bubble.state()}'")
        assertEquals("typed by the paste step", InsertedVia.PASTE, entry?.insertedVia)
        assertNull("no Paste chip after typing", Bubble.controlBounds("Paste"))
    }

    @Test
    fun aFieldThatAdaptsTheTextCountsAsTyped() {
        Scenario.field("adapting")
        val entry = dictateIntoHistory()
        assertTrue("the text arrived in capitals", Scenario.awaitFieldText("(?s).*PHOEBE.*"))
        val text = Scenario.fieldText()
        Device.screenshot("et-typing-adapting-field")
        Log.i(TAG, "adapting field: via ${entry?.insertedVia}, bubble '${Bubble.state()}'")
        assertEquals("typed by the input connection", InsertedVia.INPUT_CONNECTION, entry?.insertedVia)
        assertFalse("the field's filter left the commas out", text.contains(','))
        assertEquals("all capitals", text.uppercase(), text)
        assertNull("not handed off as well: no Paste chip", Bubble.controlBounds("Paste"))
    }

    @Test
    fun aFieldThatKeepsOnlyPartOfTheTextEndsInTheHandOff() {
        Scenario.field("length_limited")
        Scenario.dictate()
        val paste = Bubble.control("Paste", Scenario.LONG_WAIT_MS)
        val text = Scenario.fieldText()
        Device.screenshot("et-typing-length-limited")
        val entry = Arrange.latestHistory()
        Log.i(
            TAG,
            "length-limited field: kept ${text.length} chars, Paste chip at $paste, via ${entry?.insertedVia}, " +
                "History ${entry?.finalText?.length} chars",
        )
        assertEquals("handed off, not typed", InsertedVia.HANDOFF, entry?.insertedVia)
        assertTrue("the field kept only part of it", text.length <= LENGTH_LIMIT)
        assertTrue("History holds the whole dictation", (entry?.finalText?.length ?: 0) > LENGTH_LIMIT)
    }

    /**
     * Dictates and returns this dictation's History entry once it records how the text was put in (the entry is
     * written with `NONE` before the insertion and rewritten when it ends).
     */
    private fun dictateIntoHistory(): HistoryEntry? {
        val before = Arrange.latestHistory()?.id
        Scenario.dictate()
        val settled = { entry: HistoryEntry? ->
            entry != null && entry.id != before && entry.insertedVia != InsertedVia.NONE
        }
        Scenario.eventually(Scenario.LONG_WAIT_MS) { settled(Arrange.latestHistory()) }
        return Arrange.latestHistory()?.takeIf { it.id != before }
    }

    private companion object {
        /** The test host's length-limited field keeps this many characters. */
        const val LENGTH_LIMIT = 40
    }
}
