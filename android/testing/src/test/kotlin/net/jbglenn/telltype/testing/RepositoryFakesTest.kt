package net.jbglenn.telltype.testing

import app.cash.turbine.test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.DictionaryTermId
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class) // currentTime
class RepositoryFakesTest {
    @Test
    fun `settings update atomically, emit, and fail without changing`() = runTest {
        val repository = FakeSettingsRepository()

        repository.settings.test {
            assertEquals(Settings.Defaults, awaitItem())
            val updated = repository.update { it.copy(insertion = it.insertion.copy(neverUseClipboard = true)) }
            assertEquals(true, (updated as Outcome.Ok).value.insertion.neverUseClipboard)
            assertEquals(updated.value, awaitItem())
        }
        repository.faults.failure = StorageFull
        assertEquals(Outcome.Err(StorageFull), repository.update { Settings.Defaults })
        assertEquals(true, repository.current().insertion.neverUseClipboard)
    }

    @Test
    fun `history upserts by id, lists newest first, searches and enforces retention`() = runTest {
        val repository = FakeHistoryRepository()
        val old = Fixtures.historyEntry(id = "old", createdAt = Fixtures.Epoch - 31.days, finalText = "Old note.")
        val new = Fixtures.historyEntry(id = "new", finalText = "Call Sam.")

        repository.upsert(old)
        repository.upsert(new)
        repository.upsert(new.copy(finalText = "Call Sam now."))
        assertEquals(listOf("new", "old"), repository.recent(10).first().map { it.id.value })
        assertEquals("Call Sam now.", (repository.get(SessionId("new")) as Outcome.Ok).value?.finalText)
        assertEquals(listOf("old"), repository.search("OLD", 10).first().map { it.id.value })
        assertEquals(Outcome.Ok(1), repository.deleteOlderThan(Fixtures.Epoch - 30.days))
        assertEquals(Outcome.Ok(Unit), repository.delete(SessionId("missing")))
        assertEquals(1, repository.entries.size)
        assertEquals(1, repository.count().first())
        assertEquals(Outcome.Ok(1), repository.deleteAll())
        assertEquals(0, repository.count().first())
    }

    @Test
    fun `dictionary assigns ids, sorts, counts uses and rejects updates of missing rows`() = runTest {
        val repository = FakeDictionaryRepository()

        val osiris = (repository.addTerm("Osiris", listOf("o cyrus"), EntrySource.MANUAL) as Outcome.Ok).value
        repository.addTerm("Igor", emptyList(), EntrySource.LEARNED)
        val pair = (repository.addReplacement("eager", "Igor", EntrySource.LEARNED) as Outcome.Ok).value
        repository.recordUses(setOf(osiris.id), setOf(pair.id))

        assertEquals(listOf("Igor", "Osiris"), repository.terms.first().map { it.term })
        assertEquals(1, repository.terms.first().last().uses)
        assertEquals(1, repository.replacements.first().single().uses)
        assertEquals(Fixtures.Epoch, osiris.createdAt)
        assertEquals(
            Outcome.Err(StorageFailed),
            repository.updateTerm(osiris.copy(id = DictionaryTermId(99))),
        )
        assertEquals(Outcome.Ok(Unit), repository.deleteReplacement(pair.id))
        assertEquals(emptyList<Any>(), repository.replacements.first())
    }

    @Test
    fun `snippets add, update, count uses and wait for the configured delay`() = runTest {
        val repository = FakeSnippetRepository()
        repository.faults.delay = 1.seconds

        val snippet = (repository.add("my address", "1 Example Road") as Outcome.Ok).value
        repository.update(snippet.copy(text = "2 Example Road"))
        repository.recordUse(snippet.id)

        assertEquals("2 Example Road", repository.snippets.first().single().text)
        assertEquals(1, repository.snippets.first().single().uses)
        assertEquals(3_000, currentTime)
    }

    @Test
    fun `app rules upsert by package`() = runTest {
        val repository = FakeAppRuleRepository()
        val app = AppPackage("com.example.bank")
        val rule = AppRule(app, categoryOverride = null, toneOverride = null, excluded = true)

        assertEquals(Outcome.Ok(null), repository.find(app))
        repository.upsert(rule)
        repository.upsert(rule.copy(categoryOverride = AppCategory.OTHER))
        assertEquals(AppCategory.OTHER, (repository.find(app) as Outcome.Ok).value?.categoryOverride)
        assertEquals(1, repository.rules.first().size)
        repository.delete(app)
        assertNull((repository.find(app) as Outcome.Ok).value)
    }

    @Test
    fun `pending jobs queue oldest first and count failures`() = runTest {
        val repository = FakePendingJobRepository()
        val later = Fixtures.pendingJob(recording = "recording-2", createdAt = Fixtures.Epoch + 1.seconds)

        repository.enqueue(later)
        repository.enqueue(Fixtures.pendingJob())
        repository.recordFailure(RecordingId("recording-1"), "server_unreachable")

        val jobs = repository.jobs.first()
        assertEquals(listOf("recording-1", "recording-2"), jobs.map { it.recording.value })
        assertEquals(1, jobs.first().attempts)
        assertEquals("server_unreachable", jobs.first().lastErrorCode)
        assertEquals(Outcome.Err(StorageFailed), repository.recordFailure(RecordingId("missing"), "x"))
        repository.remove(RecordingId("recording-2"))
        assertEquals(1, repository.jobs.first().size)
    }
}
