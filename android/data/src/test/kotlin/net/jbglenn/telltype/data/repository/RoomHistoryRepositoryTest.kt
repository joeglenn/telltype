package net.jbglenn.telltype.data.repository

import androidx.room.Room
import app.cash.turbine.test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.data.context
import net.jbglenn.telltype.data.db.HistoryEntity
import net.jbglenn.telltype.data.db.TelltypeDatabase
import net.jbglenn.telltype.data.db.configure
import net.jbglenn.telltype.data.inMemoryDatabase
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomHistoryRepositoryTest {
    private val logger = RecordingLogger()

    private fun entry(id: String, minutes: Int, finalText: String = "Hello world.", raw: String = "hello world") =
        Fixtures.historyEntry(
            id = id,
            createdAt = Fixtures.Epoch + (minutes * 60).seconds,
            finalText = finalText,
            rawTranscript = raw,
        )

    @Test
    fun `an entry round-trips with timings, statuses and nulls`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        val full = entry("session-1", 0).copy(
            mode = DictationMode.COMMAND,
            cleanupStatus = CleanupStatus.SKIPPED_ERROR,
            insertedVia = InsertedVia.HANDOFF,
            audioDuration = 4_250.milliseconds,
            timings = SessionTimings(
                recording = 4.seconds,
                finalising = 12.5.milliseconds,
                transcription = 300.milliseconds,
                cleanup = null,
                insertion = 40.milliseconds,
                endToEnd = 1.seconds,
                server = listOf(ServerTiming("stt", 125.4.milliseconds), ServerTiming("gate", 172.1.milliseconds)),
            ),
            errorCode = "cleanup_timeout",
        )
        val failed = entry("session-2", 1).copy(cleanupStatus = null, insertedVia = InsertedVia.NONE, finalText = "")

        assertEquals(Outcome.Ok(Unit), repository.upsert(full))
        assertEquals(Outcome.Ok(Unit), repository.upsert(failed))

        assertEquals(Outcome.Ok(full), repository.get(full.id))
        assertEquals(Outcome.Ok(failed), repository.get(failed.id))
        assertEquals(Outcome.Ok(null), repository.get(SessionId("missing")))
    }

    @Test
    fun `upsert replaces the entry with the same id`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        repository.upsert(entry("session-1", 0, finalText = ""))
        repository.upsert(entry("session-1", 0, finalText = "Done."))

        assertEquals(listOf("Done."), repository.recent(10).first().map { it.finalText })
    }

    @Test
    fun `recent is newest first and limited, and emits at once and after each change`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        repository.upsert(entry("old", 0))
        repository.upsert(entry("new", 5))

        repository.recent(1).test {
            assertEquals(listOf("new"), awaitItem().map { it.id.value })
            repository.upsert(entry("newest", 9))
            assertEquals(listOf("newest"), awaitItem().map { it.id.value })
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("newest", "new", "old"), repository.recent(10).first().map { it.id.value })
    }

    @Test
    fun `search ignores case, covers final and raw text, and treats wildcards literally`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        repository.upsert(entry("a", 0, finalText = "Meet Igor at Noon.", raw = "meet igor at noon"))
        repository.upsert(entry("b", 1, finalText = "Lunch.", raw = "lunch with IGOR"))
        repository.upsert(entry("c", 2, finalText = "100% done_now", raw = "hundred percent done now"))
        repository.upsert(entry("d", 3, finalText = "Nothing here.", raw = "nothing here"))

        assertEquals(listOf("b", "a"), repository.search("igor", 10).first().map { it.id.value })
        assertEquals(listOf("a"), repository.search("NOON", 10).first().map { it.id.value })
        assertEquals(listOf("c"), repository.search("100%", 10).first().map { it.id.value })
        assertEquals(listOf("c"), repository.search("e_n", 10).first().map { it.id.value })
        assertEquals(listOf("c"), repository.search("%", 10).first().map { it.id.value })
        assertEquals(listOf("b"), repository.search("igor", 1).first().map { it.id.value })
    }

    @Test
    fun `deleting a missing entry succeeds and retention deletes only older rows`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        repository.upsert(entry("old", 0))
        repository.upsert(entry("new", 60 * 24 * 31))

        assertEquals(Outcome.Ok(Unit), repository.delete(SessionId("never-existed")))
        assertEquals(Outcome.Ok(1), repository.deleteOlderThan(Fixtures.Epoch + 1.days))
        assertEquals(listOf("new"), repository.recent(10).first().map { it.id.value })
        assertEquals(Outcome.Ok(Unit), repository.delete(SessionId("new")))
        assertEquals(emptyList<String>(), repository.recent(10).first())
    }

    @Test
    fun `an unreadable row is StorageFailed for get and skipped in lists`() = runTest {
        val database = inMemoryDatabase()
        val repository = RoomHistoryRepository(database.history(), testDispatchers(), logger)
        repository.upsert(entry("good", 0))
        database.history().upsert(
            HistoryEntity(
                id = "bad",
                createdAt = 0,
                appPackage = "x",
                category = "no_such_category",
                mode = "dictate",
                finalText = "",
                rawTranscript = "",
                cleanupStatus = null,
                insertedVia = "none",
                audioMs = 0,
                timings = "{\"server\":[]}",
                errorCode = null,
            ),
        )

        assertEquals(Outcome.Err(StorageFailed), repository.get(SessionId("bad")))
        assertEquals(listOf("good"), repository.recent(10).first().map { it.id.value })
        assertTrue(logger.entries.any { "skipped 1 unreadable rows" in it.message })
    }

    @Test
    fun `a closed database is a StorageError, not an exception, and no text is logged`() = runTest {
        val database = inMemoryDatabase()
        val repository = RoomHistoryRepository(database.history(), testDispatchers(), logger)
        database.close()

        assertEquals(Outcome.Err(StorageFailed), repository.upsert(entry("x", 0, finalText = "secret words")))
        assertTrue(logger.entries.any { "upsert failed: storage_failed" in it.message })
        assertFalse(logger.entries.any { "secret words" in it.message || "secret words" in it.throwable.toString() })
    }

    @Test
    fun `clearing counts and deletes every entry and leaves none of their text in the database files`() = runTest {
        val name = "erase-test.db"
        val database = Room.databaseBuilder(context(), TelltypeDatabase::class.java, name)
            .configure(testDispatchers().io)
            .allowMainThreadQueries()
            .build()
        val repository = RoomHistoryRepository(database.history(), testDispatchers(), logger)
        val markers = listOf("zebracorn", "quokkalith", "okapimoss")
        markers.forEachIndexed { index, marker -> repository.upsert(entry("s$index", index, "$marker.", marker)) }
        assertEquals(3, repository.count().first())
        assertTrue("the test can see stored text", markers.all { marker -> databaseFiles(name).any { marker in it } })

        assertEquals(Outcome.Ok(Unit), repository.delete(SessionId("s2")))
        assertEquals(2, repository.count().first())
        assertEquals(Outcome.Ok(2), repository.deleteAll())

        assertEquals(0, repository.count().first())
        assertEquals(emptyList<String>(), repository.recent(10).first().map { it.id.value })
        for (marker in markers) {
            assertFalse("'$marker' is left in a database file", databaseFiles(name).any { marker in it })
        }
        database.close()
    }

    @Test
    fun `a failing clear is an error, not an exception`() = runTest {
        val database = inMemoryDatabase()
        val repository = RoomHistoryRepository(database.history(), testDispatchers(), logger)
        database.close()

        assertEquals(Outcome.Err(StorageFailed), repository.deleteAll())
    }

    /** The text of the database file and its journal files, byte for byte. */
    private fun databaseFiles(name: String): List<String> {
        val directory = requireNotNull(context().getDatabasePath(name).parentFile)
        return directory.listFiles { file -> file.name.startsWith(name) }.orEmpty()
            .map { String(it.readBytes(), Charsets.ISO_8859_1) }
    }

    @Test
    fun `archived entries have their own list and search and keep their place when moved back`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        repository.upsert(entry("a", 0, finalText = "Lunch at noon."))
        repository.upsert(entry("b", 1, finalText = "Call the garage."))
        repository.upsert(entry("c", 2, finalText = "Garage opens at nine."))

        assertEquals(Outcome.Ok(Unit), repository.setArchived(SessionId("b"), archived = true))
        assertEquals(Outcome.Ok(Unit), repository.setArchived(SessionId("missing"), archived = true))

        assertEquals(listOf("c", "a"), repository.list(archived = false, limit = 10).first().map { it.id.value })
        assertEquals(listOf("b"), repository.list(archived = true, limit = 10).first().map { it.id.value })
        assertEquals(listOf("b"), repository.search("garage", archived = true, limit = 10).first().map { it.id.value })
        assertEquals(listOf("c"), repository.search("GARAGE", archived = false, limit = 10).first().map { it.id.value })
        assertEquals(1, repository.archivedCount().first())
        assertEquals(true, (repository.get(SessionId("b")) as Outcome.Ok).value?.archived)

        repository.setArchived(SessionId("b"), archived = false)
        assertEquals(listOf("c", "b", "a"), repository.list(archived = false, limit = 10).first().map { it.id.value })
        assertEquals(0, repository.archivedCount().first())
    }

    @Test
    fun `retention skips archived entries and clearing can leave the archive`() = runTest {
        val repository = RoomHistoryRepository(inMemoryDatabase().history(), testDispatchers(), logger)
        repository.upsert(entry("old", 0))
        repository.upsert(entry("old-archived", 1))
        repository.upsert(entry("new", 100))
        repository.upsert(entry("new-archived", 101))
        repository.setArchived(SessionId("old-archived"), archived = true)
        repository.setArchived(SessionId("new-archived"), archived = true)

        assertEquals(Outcome.Ok(1), repository.deleteOlderThan(Fixtures.Epoch + 50.minutes))
        assertEquals(Outcome.Ok(null), repository.get(SessionId("old")))
        assertEquals(2, repository.archivedCount().first())

        assertEquals(Outcome.Ok(1), repository.deleteAll(includeArchived = false))
        assertEquals(listOf("new-archived", "old-archived"), repository.recent(10).first().map { it.id.value })

        assertEquals(Outcome.Ok(2), repository.deleteAll(includeArchived = true))
        assertEquals(0, repository.count().first())
    }
}
