package net.jbglenn.telltype.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One `app_rule` row (Appendix E): per-app overrides and the exclusion list. */
@Entity(tableName = "app_rule")
internal data class AppRuleEntity(
    @PrimaryKey @ColumnInfo(name = "package") val appPackage: String,
    @ColumnInfo(name = "category_override") val categoryOverride: String?,
    @ColumnInfo(name = "tone_override") val toneOverride: String?,
    @ColumnInfo(name = "excluded") val excluded: Boolean,
)

/** Queries on `app_rule`. */
@Dao
internal interface AppRuleDao {
    /** Every rule, by package name. */
    @Query("SELECT * FROM app_rule ORDER BY package")
    fun rules(): Flow<List<AppRuleEntity>>

    /** The rule for [appPackage], or null. */
    @Query("SELECT * FROM app_rule WHERE package = :appPackage")
    suspend fun find(appPackage: String): AppRuleEntity?

    /** Inserts or replaces by package. */
    @Upsert
    suspend fun upsert(entity: AppRuleEntity)

    /** Deletes the rule for [appPackage]; returns how many rows were deleted. */
    @Query("DELETE FROM app_rule WHERE package = :appPackage")
    suspend fun delete(appPackage: String): Int
}
