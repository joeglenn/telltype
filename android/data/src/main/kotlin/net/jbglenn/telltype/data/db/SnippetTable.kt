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

/** One `snippet` row (Appendix E). The text is personal data, so [toString] prints lengths only. */
@Entity(tableName = "snippet", indices = [Index(value = ["trigger"])])
internal data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    @ColumnInfo(name = "trigger") val trigger: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "uses") val uses: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun toString(): String =
        "SnippetEntity(id=$id, trigger=‹${trigger.length} chars›, text=‹${text.length} chars›, uses=$uses)"
}

/** Queries on `snippet`. */
@Dao
internal interface SnippetDao {
    /** Every snippet, alphabetical by trigger, ignoring case. */
    @Query("SELECT * FROM snippet ORDER BY trigger COLLATE NOCASE, id")
    fun snippets(): Flow<List<SnippetEntity>>

    /** Inserts a snippet (id 0 = assign one); returns the new id. */
    @Insert
    suspend fun insert(entity: SnippetEntity): Long

    /** Replaces the snippet with the same id; returns how many rows changed. */
    @Update
    suspend fun update(entity: SnippetEntity): Int

    /** Deletes a snippet; returns how many rows were deleted. */
    @Query("DELETE FROM snippet WHERE id = :id")
    suspend fun delete(id: Long): Int

    /** Adds one use. */
    @Query("UPDATE snippet SET uses = uses + 1 WHERE id = :id")
    suspend fun addUse(id: Long): Int
}
