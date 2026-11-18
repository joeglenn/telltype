package net.jbglenn.telltype.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** One `dictionary_term` row (Appendix E). `sounds_like` is a JSON list of strings. */
@Entity(tableName = "dictionary_term", indices = [Index(value = ["term"])])
internal data class DictionaryTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "term") val term: String,
    @ColumnInfo(name = "sounds_like") val soundsLike: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "uses") val uses: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** One `replacement` row (Appendix E). */
@Entity(tableName = "replacement", indices = [Index(value = ["heard"])])
internal data class ReplacementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "heard") val heard: String,
    @ColumnInfo(name = "replace_with") val replaceWith: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "uses") val uses: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Queries on `dictionary_term` and `replacement`. Lists are alphabetical, ignoring case. */
@Dao
internal interface DictionaryDao {
    /** Every term. */
    @Query("SELECT * FROM dictionary_term ORDER BY term COLLATE NOCASE, id")
    fun terms(): Flow<List<DictionaryTermEntity>>

    /** Inserts a term (id 0 = assign one); returns the new id. */
    @Insert
    suspend fun insertTerm(entity: DictionaryTermEntity): Long

    /** Replaces the term with the same id; returns how many rows changed (0 when it does not exist). */
    @Update
    suspend fun updateTerm(entity: DictionaryTermEntity): Int

    /** Deletes a term; returns how many rows were deleted. */
    @Query("DELETE FROM dictionary_term WHERE id = :id")
    suspend fun deleteTerm(id: Long): Int

    /** Adds one use to each listed term. */
    @Query("UPDATE dictionary_term SET uses = uses + 1 WHERE id IN (:ids)")
    suspend fun addTermUses(ids: List<Long>): Int

    /** Every replacement. */
    @Query("SELECT * FROM replacement ORDER BY heard COLLATE NOCASE, id")
    fun replacements(): Flow<List<ReplacementEntity>>

    /** Inserts a replacement (id 0 = assign one); returns the new id. */
    @Insert
    suspend fun insertReplacement(entity: ReplacementEntity): Long

    /** Replaces the replacement with the same id; returns how many rows changed. */
    @Update
    suspend fun updateReplacement(entity: ReplacementEntity): Int

    /** Deletes a replacement; returns how many rows were deleted. */
    @Query("DELETE FROM replacement WHERE id = :id")
    suspend fun deleteReplacement(id: Long): Int

    /** Adds one use to each listed replacement. */
    @Query("UPDATE replacement SET uses = uses + 1 WHERE id IN (:ids)")
    suspend fun addReplacementUses(ids: List<Long>): Int
}
