package net.jbglenn.telltype.data.mapping

import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteFullException
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.data.db.AppRuleEntity
import net.jbglenn.telltype.data.db.DictionaryTermEntity
import net.jbglenn.telltype.data.db.PendingJobEntity
import net.jbglenn.telltype.data.storage.storageErrorOf
import net.jbglenn.telltype.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MappingTest {
    @Test
    fun `timings round-trip through JSON, absent stages stay null`() {
        val timings = SessionTimings(
            recording = 4.seconds,
            finalising = null,
            transcription = 312.5.milliseconds,
            cleanup = null,
            insertion = 0.milliseconds,
            endToEnd = 1.2.seconds,
            server = listOf(ServerTiming("decode", 44.milliseconds), ServerTiming("stt", 125.4.milliseconds)),
        )
        assertEquals(timings, decodeTimings(encodeTimings(timings)))
        assertEquals(SessionTimings.None, decodeTimings(encodeTimings(SessionTimings.None)))
        assertNull(decodeTimings("[]"))
        assertNull(decodeTimings("""{"server":[{"name":3,"ms":1}]}"""))
        assertNull(decodeTimings("not json"))
    }

    @Test
    fun `string lists round-trip and bad ones are unreadable`() {
        assertEquals(
            listOf("o siris", "", "\"quoted\""),
            decodeStringList(encodeStringList(listOf("o siris", "", "\"quoted\""))),
        )
        assertNull(decodeStringList("""["a", 1]"""))
        assertNull(decodeStringList("""{"a":1}"""))
    }

    @Test
    fun `models map to rows with stable ids and back`() {
        val job = Fixtures.pendingJob().copy(attempts = 3, lastErrorCode = "server_unreachable")
        assertEquals(job, job.toEntity().toModel())
        val recovered = job.copy(app = null, incognito = true)
        assertEquals(recovered, recovered.toEntity().toModel())
        assertEquals(null, recovered.toEntity().appPackage)
        assertEquals("personal_chat", job.toEntity().category)
        assertEquals("casual", job.toEntity().tone)

        val rule = AppRule(AppPackage("a.b"), AppCategory.CODE, Tone.AS_SPOKEN, true)
        assertEquals(AppRuleEntity("a.b", "code", "as_spoken", true), rule.toEntity())
        assertEquals(rule, rule.toEntity().toModel())

        val entry = Fixtures.historyEntry()
        assertEquals(entry, entry.toEntity().toModel())
    }

    @Test
    fun `rows with unknown ids are unreadable rather than guessed`() {
        assertNull(AppRuleEntity("a.b", "spaceships", null, false).toModel())
        assertNull(PendingJobEntity("r", "s", 0, "/p", "email", "loud", 0, null, null, false).toModel())
        assertNull(DictionaryTermEntity(1, "t", "not a list", "manual", 0, 0).toModel())
        assertNull(DictionaryTermEntity(1, "t", "[]", "guessed", 0, 0).toModel())
        assertNull(Fixtures.historyEntry().toEntity().copy(insertedVia = "telepathy").toModel())
        assertNull(Fixtures.historyEntry().toEntity().copy(audioMs = -1).toModel())
    }

    @Test
    fun `storage failures map to StorageFull or StorageFailed`() {
        val rows = listOf(
            SQLiteFullException("database or disk is full") to StorageFull,
            IOException("write failed: ENOSPC (No space left on device)") to StorageFull,
            IOException("rename failed", IOException("No space left on device")) to StorageFull,
            SQLiteDiskIOException("disk I/O error") to StorageFailed,
            SQLiteDatabaseCorruptException("file is not a database") to StorageFailed,
            IOException("Permission denied") to StorageFailed,
            IllegalStateException("attempt to re-open an already-closed object") to StorageFailed,
        )
        for ((failure, expected) in rows) {
            assertEquals("mapping of $failure", expected, storageErrorOf(failure))
        }
    }
}
