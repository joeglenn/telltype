package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The learning window: edits of the dictated text become \"Learn X → Y\" offers or learned replacements. */
class LearningTest {
    private val inserted = "We ship it on Friday."
    private val corrected = Correction("Friday", "Monday")

    private fun learning(mode: LearnMode): Settings =
        configuredSettings().let { it.copy(dictionary = it.dictionary.copy(learnFromCorrections = mode)) }

    @Test
    fun `a one-word correction is offered and learned when accepted`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText(inserted)))
        w.start()
        w.dictate()
        w.editWatcher.type("We ship it on Monday.")
        w.editWatcher.type("We ship it on Monday.")
        w.runCurrent()
        assertEquals(listOf<Chip>(Chip.LearnOffer(corrected)), w.chips)
        w.send(SessionEvent.LearnAccepted(corrected))
        w.idle()
        val learned = w.dictionary.replacements.first().single()
        assertEquals("Friday" to "Monday", learned.heard to learned.replaceWith)
        assertEquals(EntrySource.LEARNED, learned.source)
        assertEquals(60.seconds, w.editWatcher.watches.single().second)
        w.assertNothingRunning()
        w.closeAndAssertStopped()
    }

    @Test
    fun `automatic learning saves without asking and off does not watch`() = runTest {
        val auto = CoordinatorWorld(this, settings = learning(LearnMode.AUTOMATIC))
        auto.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText(inserted)))
        auto.start()
        auto.dictate()
        auto.editWatcher.type("We ship it on Monday.")
        auto.idle()
        assertTrue(auto.chips.isEmpty())
        assertEquals("Monday", auto.dictionary.replacements.first().single().replaceWith)
        auto.closeAndAssertStopped()

        val off = CoordinatorWorld(this, settings = learning(LearnMode.OFF))
        off.start()
        off.dictate()
        off.idle()
        assertTrue(off.editWatcher.watches.isEmpty())
        off.closeAndAssertStopped()
    }

    @Test
    fun `a new dictation ends the previous watch`() = runTest {
        val w = CoordinatorWorld(this)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText(inserted)))
        w.start()
        w.dictate()
        w.advance(5.seconds)
        w.send(SessionEvent.MicPressed)
        w.editWatcher.type("We ship it on Monday.")
        w.runCurrent()
        assertTrue(w.chips.isEmpty())
        w.closeAndAssertStopped()
    }
}
