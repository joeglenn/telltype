package net.jbglenn.telltype.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One `pending_job` row (Appendix E with D22: `id` is the recording id, plus `session_id`). Schema 2 (A7h) adds
 * `app_package` (null when unknown) and `incognito`; a row migrated from schema 1 gets `incognito = 1`, because
 * whether its field was incognito is unknown and such a job must never be saved to History.
 */
@Entity(tableName = "pending_job", indices = [Index(value = ["created_at"])])
internal data class PendingJobEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "audio_path") val audioPath: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "tone") val tone: String,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "app_package") val appPackage: String?,
    @ColumnInfo(name = "incognito", defaultValue = "1") val incognito: Boolean,
)

/** Queries on `pending_job`. */
@Dao
internal interface PendingJobDao {
    /** Every job, oldest first. */
    @Query("SELECT * FROM pending_job ORDER BY created_at, id")
    fun jobs(): Flow<List<PendingJobEntity>>

    /** Inserts or replaces by recording id. */
    @Upsert
    suspend fun upsert(entity: PendingJobEntity)

    /** Adds one failed attempt; returns how many rows changed (0 when the job does not exist). */
    @Query("UPDATE pending_job SET attempts = attempts + 1, last_error = :errorCode WHERE id = :id")
    suspend fun recordFailure(id: String, errorCode: String): Int

    /** Deletes a job; returns how many rows were deleted. */
    @Query("DELETE FROM pending_job WHERE id = :id")
    suspend fun delete(id: String): Int
}
