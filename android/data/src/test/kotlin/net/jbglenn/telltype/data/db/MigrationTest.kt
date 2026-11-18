package net.jbglenn.telltype.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.data.context
import net.jbglenn.telltype.data.mapping.toModel
import net.jbglenn.telltype.data.repository.RoomHistoryRepository
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The migration harness (docs/PLAN.md Appendix E: schema changes ship with tested migrations): every exported
 * schema exists, data written with the oldest schema is read by the current code, and each migration keeps its
 * table's rows (1 to 2: `pending_job` gains `app_package` and `incognito`, old rows count as incognito; 2 to 3:
 * `app_rule` gets the exclusions added in schema 3 without touching the user's rules; 3 to 4: `history` gets
 * `archived`, false for every existing entry).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), TelltypeDatabase::class.java)

    private val name = "migration-test.db"

    @Test
    fun `every version has an exported schema and a migration from the one before`() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        for (version in 1..TelltypeDatabase.VERSION) {
            assets.open("${TelltypeDatabase::class.java.name}/$version.json").close()
        }
        val steps = Migrations.All.map { it.startVersion to it.endVersion }
        assertEquals((1 until TelltypeDatabase.VERSION).map { it to it + 1 }, steps)
    }

    @Test
    fun `schema 1 to 2 keeps queued jobs, with an unknown app and counted as incognito`() = runTest {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO pending_job (id, session_id, created_at, audio_path, category, tone, attempts, " +
                    "last_error) VALUES ('recording-1', 'session-1', 1763370000000, '/data/r.wav', 'other', " +
                    "'casual', 2, 'unexpected')",
            )
        }

        helper.runMigrationsAndValidate(name, 2, true, Migrations.MIGRATION_1_2).close()

        val database = Room.databaseBuilder(context(), TelltypeDatabase::class.java, name)
            .configure(testDispatchers().io)
            .allowMainThreadQueries()
            .build()
        helper.closeWhenFinished(database)
        val job = database.pendingJobs().jobs().first().single().toModel()
        assertEquals(RecordingId("recording-1"), job?.recording)
        assertEquals(2, job?.attempts)
        assertEquals(null, job?.app)
        assertEquals(true, job?.incognito)
    }

    @Test
    fun `a database created from the oldest schema migrates to the current one and keeps its rows`() = runTest {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO history (id, created_at, app_package, category, mode, final_text, raw_transcript, " +
                    "cleanup_status, inserted_via, audio_ms, timings, error_code) VALUES " +
                    "('session-1', 1763370000000, 'org.example.chat', 'personal_chat', 'dictate', 'Hello.', 'hello', " +
                    "'applied', 'input_connection', 3000, '{\"server\":[]}', NULL)",
            )
        }

        helper.runMigrationsAndValidate(name, TelltypeDatabase.VERSION, true, *Migrations.All).close()

        val database = Room.databaseBuilder(context(), TelltypeDatabase::class.java, name)
            .configure(testDispatchers().io)
            .allowMainThreadQueries()
            .build()
        helper.closeWhenFinished(database)
        val repository = RoomHistoryRepository(database.history(), testDispatchers(), RecordingLogger())
        val entry = (repository.get(SessionId("session-1")) as Outcome.Ok).value
        assertEquals("Hello.", entry?.finalText)
        assertEquals(
            "the first seed only runs on creation; migrating adds only the schema 3 exclusions",
            ExclusionSeed.AddedInVersion3.sorted(),
            database.appRules().rules().first().map { it.appPackage },
        )
    }

    @Test
    fun `schema 2 to 3 adds the new exclusions and keeps the rules the user made or removed`() = runTest {
        helper.createDatabase(name, 2).use { db ->
            db.execSQL(
                "INSERT INTO app_rule (package, category_override, tone_override, excluded) VALUES " +
                    "('com.onepassword.android', NULL, NULL, 1), " +
                    "('com.callpod.android_apps.keeper', 'work_chat', NULL, 0), " +
                    "('org.example.chat', 'personal_chat', 'casual', 0)",
            )
        }

        helper.runMigrationsAndValidate(name, 3, true, Migrations.MIGRATION_2_3).close()

        val database = Room.databaseBuilder(context(), TelltypeDatabase::class.java, name)
            .configure(testDispatchers().io)
            .allowMainThreadQueries()
            .build()
        helper.closeWhenFinished(database)
        val rules = database.appRules().rules().first().associateBy { it.appPackage }
        assertEquals(true, rules.getValue("proton.android.pass").excluded)
        assertEquals(null, rules.getValue("proton.android.pass").categoryOverride)
        assertEquals("the user's own rule for a newly listed app stays", false, rules.getValue(KEEPER).excluded)
        assertEquals("work_chat", rules.getValue(KEEPER).categoryOverride)
        assertEquals("casual", rules.getValue("org.example.chat").toneOverride)
        assertFalse("a first-seed rule the user removed stays removed", "com.x8bit.bitwarden" in rules)
        assertEquals(
            (ExclusionSeed.AddedInVersion3 + "com.onepassword.android" + "org.example.chat").sorted(),
            rules.keys.sorted(),
        )
    }

    @Test
    fun `schema 3 to 4 keeps every history entry, none of them archived`() = runTest {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO history (id, created_at, app_package, category, mode, final_text, raw_transcript, " +
                    "cleanup_status, inserted_via, audio_ms, timings, error_code) VALUES " +
                    "('session-1', 1763370000000, 'org.example.chat', 'personal_chat', 'dictate', 'Hello.', 'hello', " +
                    "'applied', 'input_connection', 3000, '{\"server\":[]}', NULL)",
            )
        }

        helper.runMigrationsAndValidate(name, 4, true, Migrations.MIGRATION_3_4).close()

        val database = Room.databaseBuilder(context(), TelltypeDatabase::class.java, name)
            .configure(testDispatchers().io)
            .allowMainThreadQueries()
            .build()
        helper.closeWhenFinished(database)
        val repository = RoomHistoryRepository(database.history(), testDispatchers(), RecordingLogger())
        val entry = (repository.get(SessionId("session-1")) as Outcome.Ok).value
        assertEquals("Hello.", entry?.finalText)
        assertEquals(false, entry?.archived)
        assertEquals(0, repository.archivedCount().first())
        val listed = repository.list(archived = false, limit = 10).first()
        assertEquals(listOf(SessionId("session-1")), listed.map { it.id })
    }

    private companion object {
        const val KEEPER = "com.callpod.android_apps.keeper"
    }
}
