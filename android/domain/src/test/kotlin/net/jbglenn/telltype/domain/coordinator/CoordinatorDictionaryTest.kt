package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Package V1, feature F2 (docs/PLAN.md section 4.5): a dictionary term's "sounds like" forms are the final exact
 * replacement, after the model and after the rules, without touching any stored replacement's use count.
 */
class CoordinatorDictionaryTest {
    @Test
    fun `a sounds-like form the model kept is replaced by the term`() = runTest {
        val w = CoordinatorWorld(this)
        w.dictionary.addTerm("Dagster", listOf("Daxter", "dag star"), EntrySource.MANUAL)
        w.cleaner.cleanCall.returns(Outcome.Ok(Fixtures.cleanedText("I pushed the fix to the Daxter pipeline.")))
        w.start()
        w.dictate("I pushed the fix to the Daxter pipeline")
        assertEquals("I pushed the fix to the Dagster pipeline.", (w.state as SessionState.Done).receipt.text)
        w.idle()
        assertEquals(1, w.dictionary.terms.first().single().uses)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a lower-case sounds-like form matches at a sentence start and in the rule-based path`() = runTest {
        val w = CoordinatorWorld(this)
        w.dictionary.addTerm("Dagster", listOf("dag star"), EntrySource.MANUAL)
        w.dictionary.addReplacement("pipe line", "pipeline", EntrySource.MANUAL)
        w.settingsRepository.update { it.copy(cleanup = it.cleanup.copy(enabled = false)) }
        w.start()
        w.dictate("dag star runs the pipe line")
        assertEquals("Dagster runs the pipeline", (w.state as SessionState.Done).receipt.text.trimEnd('.'))
        w.idle()
        assertEquals(1, w.dictionary.replacements.first().single().uses, "the stored pair counts once")
        w.closeAndAssertStopped()
    }
}
