package net.jbglenn.telltype.data.repository

import app.cash.turbine.test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.getOrNull
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.ReplacementId
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.data.inMemoryDatabase
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.FixedClock
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomDictionaryRepositoryTest {
    private val clock = FixedClock()

    @Test
    fun `adding a term assigns an id and the clock's time, with zero uses`() = runTest {
        val repository = RoomDictionaryRepository(inMemoryDatabase(), clock, testDispatchers(), RecordingLogger())
        clock.advanceBy(5.minutes)

        val first = repository.addTerm("Osiris", listOf("o siris", "osiris"), EntrySource.MANUAL).getOrNull()!!
        val second = repository.addTerm("Igor", emptyList(), EntrySource.LEARNED).getOrNull()!!

        assertNotEquals(first.id, second.id)
        assertEquals(Fixtures.Epoch + 5.minutes, first.createdAt)
        assertEquals(0, first.uses)
        assertEquals(listOf("o siris", "osiris"), first.soundsLike)
        assertEquals("alphabetical, ignoring case", listOf(second, first), repository.terms.first())
    }

    @Test
    fun `terms flow emits at once and after each change`() = runTest {
        val repository = RoomDictionaryRepository(inMemoryDatabase(), clock, testDispatchers(), RecordingLogger())

        repository.terms.test {
            assertEquals(emptyList<Any>(), awaitItem())
            repository.addTerm("zeta", emptyList(), EntrySource.MANUAL)
            assertEquals(listOf("zeta"), awaitItem().map { it.term })
            repository.addTerm("Alpha", emptyList(), EntrySource.MANUAL)
            assertEquals(listOf("Alpha", "zeta"), awaitItem().map { it.term })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `updating a missing term fails, deleting a missing term succeeds`() = runTest {
        val repository = RoomDictionaryRepository(inMemoryDatabase(), clock, testDispatchers(), RecordingLogger())
        val term = repository.addTerm("Oslo", emptyList(), EntrySource.MANUAL).getOrNull()!!

        assertEquals(Outcome.Ok(Unit), repository.updateTerm(term.copy(term = "OSLO", uses = 3)))
        assertEquals(listOf(term.copy(term = "OSLO", uses = 3)), repository.terms.first())
        assertEquals(Outcome.Err(StorageFailed), repository.updateTerm(term.copy(id = DictionaryTermId(999))))
        assertEquals(Outcome.Ok(Unit), repository.deleteTerm(DictionaryTermId(999)))
        assertEquals(Outcome.Ok(Unit), repository.deleteTerm(term.id))
        assertEquals(emptyList<Any>(), repository.terms.first())
    }

    @Test
    fun `replacements follow the same rules`() = runTest {
        val repository = RoomDictionaryRepository(inMemoryDatabase(), clock, testDispatchers(), RecordingLogger())
        val replacement = repository.addReplacement("hugin", "Huginn", EntrySource.LEARNED).getOrNull()!!
        repository.addReplacement("Adam", "Oslo", EntrySource.MANUAL)

        assertEquals(listOf("Adam", "hugin"), repository.replacements.first().map { it.heard })
        assertEquals(Outcome.Ok(Unit), repository.updateReplacement(replacement.copy(replaceWith = "Muninn")))
        assertEquals(Outcome.Err(StorageFailed), repository.updateReplacement(replacement.copy(id = ReplacementId(42))))
        assertEquals(Outcome.Ok(Unit), repository.deleteReplacement(ReplacementId(42)))
        assertEquals(Outcome.Ok(Unit), repository.deleteReplacement(replacement.id))
        assertEquals(listOf("Adam"), repository.replacements.first().map { it.heard })
    }

    @Test
    fun `recordUses adds one use each and ignores unknown ids`() = runTest {
        val repository = RoomDictionaryRepository(inMemoryDatabase(), clock, testDispatchers(), RecordingLogger())
        val term = repository.addTerm("Oslo", emptyList(), EntrySource.MANUAL).getOrNull()!!
        val replacement = repository.addReplacement("oslo", "Oslo", EntrySource.MANUAL).getOrNull()!!

        assertEquals(
            Outcome.Ok(Unit),
            repository.recordUses(setOf(term.id, DictionaryTermId(77)), setOf(replacement.id)),
        )
        repository.recordUses(setOf(term.id), emptySet())

        assertEquals(2, repository.terms.first().single().uses)
        assertEquals(1, repository.replacements.first().single().uses)
        assertEquals(Outcome.Ok(Unit), repository.recordUses(emptySet(), emptySet()))
    }
}
