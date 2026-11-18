package net.jbglenn.telltype.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * One `history` row (docs/PLAN.md Appendix E, with `skipped_error` as a `cleanup_status` value, D22). Enum columns
 * hold the stable ids of the model enums; `timings` is JSON; times are epoch milliseconds; `archived` (schema 4, D52)
 * marks Archive rows, which the retention job keeps. Holds dictated text, so [toString] prints lengths only.
 */
@Entity(tableName = "history", indices = [Index(value = ["created_at"])])
internal data class HistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "app_package") val appPackage: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "final_text") val finalText: String,
    @ColumnInfo(name = "raw_transcript") val rawTranscript: String,
    @ColumnInfo(name = "cleanup_status") val cleanupStatus: String?,
    @ColumnInfo(name = "inserted_via") val insertedVia: String,
    @ColumnInfo(name = "audio_ms") val audioMs: Long,
    @ColumnInfo(name = "timings") val timings: String,
    @ColumnInfo(name = "error_code") val errorCode: String?,
    @ColumnInfo(name = "archived", defaultValue = "0") val archived: Boolean = false,
) {
    override fun toString(): String =
        "HistoryEntity(id=$id, createdAt=$createdAt, finalText=‹${finalText.length} chars›, " +
            "rawTranscript=‹${rawTranscript.length} chars›, errorCode=$errorCode, archived=$archived)"
}

/** Queries on `history`. Lists are newest first. */
@Dao
@Suppress("TooManyFunctions") // One function per History query; the Archive (D52) adds its own.
internal interface HistoryDao {
    /** The newest [limit] rows. */
    @Query("SELECT * FROM history ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<HistoryEntity>>

    /**
     * Rows whose final text or raw transcript contains [pattern], a `LIKE` pattern escaped with `\`. SQLite's
     * `LIKE` ignores case (for ASCII letters).
     */
    @Query(
        "SELECT * FROM history " +
            "WHERE final_text LIKE :pattern ESCAPE '\\' OR raw_transcript LIKE :pattern ESCAPE '\\' " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit",
    )
    fun search(pattern: String, limit: Int): Flow<List<HistoryEntity>>

    /** One row, or null. */
    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun get(id: String): HistoryEntity?

    /** Inserts or replaces by id. */
    @Upsert
    suspend fun upsert(entity: HistoryEntity)

    /** Deletes one row; returns how many were deleted (0 or 1). */
    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Deletes rows created before [cutoff] (epoch milliseconds) that are not archived; returns how many. */
    @Query("DELETE FROM history WHERE created_at < :cutoff AND archived = 0")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** How many rows there are. */
    @Query("SELECT COUNT(*) FROM history")
    fun count(): Flow<Int>

    /** Deletes every row; returns how many. */
    @Query("DELETE FROM history")
    suspend fun deleteAll(): Int

    /** The newest [limit] rows with this [archived] mark. */
    @Query("SELECT * FROM history WHERE archived = :archived ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun list(archived: Boolean, limit: Int): Flow<List<HistoryEntity>>

    /** Like [search], among the rows with this [archived] mark. */
    @Query(
        "SELECT * FROM history WHERE archived = :archived " +
            "AND (final_text LIKE :pattern ESCAPE '\\' OR raw_transcript LIKE :pattern ESCAPE '\\') " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit",
    )
    fun search(pattern: String, archived: Boolean, limit: Int): Flow<List<HistoryEntity>>

    /** Sets the [archived] mark of one row; returns how many rows changed (0 or 1). */
    @Query("UPDATE history SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean): Int

    /** How many rows are archived. */
    @Query("SELECT COUNT(*) FROM history WHERE archived = 1")
    fun archivedCount(): Flow<Int>

    /** Deletes every row that is not archived; returns how many. */
    @Query("DELETE FROM history WHERE archived = 0")
    suspend fun deleteUnarchived(): Int

    /**
     * Runs one `PRAGMA` and returns the first column of its first row. Used for `wal_checkpoint(TRUNCATE)` after a
     * deletion, so no old copy of deleted text stays in the write-ahead log.
     */
    @RawQuery
    suspend fun pragma(query: SupportSQLiteQuery): Int
}

/** The `PRAGMA`s that keep deleted History text from staying readable in the database files. */
internal object HistoryPragmas {
    /** Deleted rows are overwritten with zeros; set on every open ([SecureDelete]). */
    const val SECURE_DELETE = "PRAGMA secure_delete = ON"

    /** Copies the write-ahead log into the database file and empties it, so deleted text is gone from both. */
    val TruncateLog: SimpleSQLiteQuery = SimpleSQLiteQuery("PRAGMA wal_checkpoint(TRUNCATE)")
}
