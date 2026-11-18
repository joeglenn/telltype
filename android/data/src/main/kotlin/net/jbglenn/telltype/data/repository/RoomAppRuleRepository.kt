package net.jbglenn.telltype.data.repository

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.map
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.data.db.AppRuleDao
import net.jbglenn.telltype.data.mapping.toEntity
import net.jbglenn.telltype.data.mapping.toModel
import net.jbglenn.telltype.data.storage.StorageCalls
import net.jbglenn.telltype.domain.port.AppRuleRepository

/** [AppRuleRepository] on the Room `app_rule` table, which is seeded with the Appendix J exclusions. */
internal class RoomAppRuleRepository @Inject constructor(
    private val dao: AppRuleDao,
    dispatchers: DispatcherProvider,
    logger: Logger,
) : AppRuleRepository {
    private val calls = StorageCalls(dispatchers, logger, LogTag("AppRuleRepo"))

    override val rules: Flow<List<AppRule>> = calls.list("rules", dao.rules()) { it.toModel() }

    override suspend fun find(app: AppPackage): Outcome<AppRule?, StorageError> = calls.runFlat("find") {
        val row = dao.find(app.name) ?: return@runFlat Outcome.Ok(null)
        row.toModel()?.let { Outcome.Ok(it) } ?: Outcome.Err(StorageFailed)
    }

    override suspend fun upsert(rule: AppRule): Outcome<Unit, StorageError> =
        calls.run("upsert") { dao.upsert(rule.toEntity()) }

    override suspend fun delete(app: AppPackage): Outcome<Unit, StorageError> = calls.run("delete") {
        dao.delete(app.name)
    }.map { }
}
