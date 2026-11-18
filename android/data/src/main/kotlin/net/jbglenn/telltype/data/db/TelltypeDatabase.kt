package net.jbglenn.telltype.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlin.coroutines.CoroutineContext

/**
 * The app's Room database (docs/PLAN.md Appendix E). The schema of every version is exported to `data/schemas/`
 * and kept in the repository; a version change ships with a migration in [Migrations.All] and a test in
 * `MigrationTest`.
 */
@Database(
    entities = [
        HistoryEntity::class,
        DictionaryTermEntity::class,
        ReplacementEntity::class,
        SnippetEntity::class,
        AppRuleEntity::class,
        PendingJobEntity::class,
    ],
    version = TelltypeDatabase.VERSION,
    exportSchema = true,
)
internal abstract class TelltypeDatabase : RoomDatabase() {
    /** The `history` table. */
    abstract fun history(): HistoryDao

    /** The `dictionary_term` and `replacement` tables. */
    abstract fun dictionary(): DictionaryDao

    /** The `snippet` table. */
    abstract fun snippets(): SnippetDao

    /** The `app_rule` table. */
    abstract fun appRules(): AppRuleDao

    /** The `pending_job` table. */
    abstract fun pendingJobs(): PendingJobDao

    companion object {
        /** The current schema version. */
        const val VERSION = 4

        /** The database file name in the app's database directory. */
        const val FILE_NAME = "telltype.db"

        /** Opens the database file; every query runs on [queryContext] (the io dispatcher). */
        fun open(context: Context, queryContext: CoroutineContext): TelltypeDatabase =
            Room.databaseBuilder(context, TelltypeDatabase::class.java, FILE_NAME)
                .configure(queryContext)
                .build()
    }
}

/**
 * The configuration every builder of [TelltypeDatabase] gets, tests included: context, migrations, seed, secure
 * deletion.
 */
internal fun RoomDatabase.Builder<TelltypeDatabase>.configure(
    queryContext: CoroutineContext,
): RoomDatabase.Builder<TelltypeDatabase> = apply {
    setQueryCoroutineContext(queryContext)
    Migrations.All.forEach { addMigrations(it) }
    addCallback(ExclusionSeed)
    addCallback(SecureDelete)
}

/**
 * Turns on `secure_delete` whenever the database opens, so deleted rows (History cleared, entries past retention)
 * are overwritten with zeros instead of staying readable in free pages. `PRAGMA` statements run on the connection
 * that writes.
 */
internal object SecureDelete : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        db.query(HistoryPragmas.SECURE_DELETE).use { it.moveToFirst() }
    }
}

/** Every schema migration, oldest first. */
internal object Migrations {
    /**
     * 1 to 2 (A7h): `pending_job` gets `app_package` (unknown for old rows) and `incognito`. Old rows count as
     * incognito: nobody knows whether their field was, and a job that is never saved to History loses nothing
     * (its text is still offered in the notification, as before).
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE pending_job ADD COLUMN app_package TEXT")
            db.execSQL("ALTER TABLE pending_job ADD COLUMN incognito INTEGER NOT NULL DEFAULT 1")
        }
    }

    /**
     * 2 to 3 (A8b): no table changes; `app_rule` gets the exclusions added to [ExclusionSeed] in schema 3 (the seed
     * runs only when a database is created). `INSERT OR IGNORE` leaves every rule the user already has, for these
     * packages too, and does not bring back a first-seed rule the user removed.
     */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            ExclusionSeed.insert(db, ExclusionSeed.AddedInVersion3)
        }
    }

    /**
     * 3 to 4 (A9a, D52): `history` gets `archived`; every existing entry stays in History (not archived), so the
     * retention job treats it as before.
     */
    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE history ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** Passed to the builder and to the migration tests. */
    val All: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}

/**
 * Seeds `app_rule` with the exclusions of docs/PLAN.md Appendix J when the database is first created: password
 * managers and authenticators never show the bubble. The user can remove or add rules later. Google Password
 * Manager lives inside Play services and cannot be excluded by package.
 */
internal object ExclusionSeed : RoomDatabase.Callback() {
    /** The excluded packages of Appendix J, seeded since schema 1. */
    val FirstPackages: List<String> = listOf(
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.lastpass.lpandroid",
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
    )

    /** Password managers and authenticators added in schema 3 (A8b); [Migrations.MIGRATION_2_3] adds them too. */
    val AddedInVersion3: List<String> = listOf(
        "proton.android.pass",
        "proton.android.authenticator",
        "com.bitwarden.authenticator",
        "com.callpod.android_apps.keeper",
        "com.dashlane",
        "com.nordpass.android.app.password.manager",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "com.kunzisoft.keepass.pro",
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "io.enpass.app",
        "com.siber.roboform",
        "com.beemdevelopment.aegis",
        "com.azure.authenticator",
        "com.twofasapp",
        "io.ente.auth",
        "com.lastpass.authenticator",
        "com.duosecurity.duomobile",
        "org.fedorahosted.freeotp",
        "org.liberty.android.freeotpplus",
    )

    /** Every package a new database is seeded with. */
    val Packages: List<String> = FirstPackages + AddedInVersion3

    override fun onCreate(db: SupportSQLiteDatabase) {
        insert(db, Packages)
    }

    /** Adds an exclusion rule for each of [packages] that has no rule yet. */
    fun insert(db: SupportSQLiteDatabase, packages: List<String>) {
        for (appPackage in packages) {
            db.execSQL(
                "INSERT OR IGNORE INTO app_rule (package, category_override, tone_override, excluded) " +
                    "VALUES (?, NULL, NULL, 1)",
                arrayOf(appPackage),
            )
        }
    }
}
