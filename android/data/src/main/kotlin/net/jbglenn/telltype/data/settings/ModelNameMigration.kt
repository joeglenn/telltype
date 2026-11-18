package net.jbglenn.telltype.data.settings

import javax.inject.Inject
import kotlinx.coroutines.flow.filterIsInstance
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.settings.ModelChoices

/**
 * Replaces saved model choices that still hold the deprecated gate aliases (`cleanup`, `assistant`) with the
 * server's real model names after the next successful health check (brief M1): Telltype Server 1.0.0 no longer
 * lists the aliases. Runs on every successful check, but changes the settings only while an alias is saved, so
 * the replacement happens once; the rule is [ModelChoices.replaceAliases].
 */
public class ModelNameMigration @Inject constructor(
    private val settings: SettingsRepository,
    private val health: ServerHealthSource,
    private val logger: Logger,
) {
    /** Follows the server's reachability until cancelled. */
    public suspend fun run() {
        health.reachability.filterIsInstance<ServerReachability.Reachable>().collect(::apply)
    }

    /** Applies the replacement for one successful check. */
    internal suspend fun apply(check: ServerReachability.Reachable) {
        val serverCleanup = check.health?.cleanup?.model
        val before = settings.current().server.models
        if (ModelChoices.replaceAliases(before, check.offered.text, serverCleanup) == before) return
        val outcome = settings.update {
            val models = ModelChoices.replaceAliases(it.server.models, check.offered.text, serverCleanup)
            it.copy(server = it.server.copy(models = models))
        }
        when (outcome) {
            is Outcome.Ok -> logger.info(TAG) {
                val after = outcome.value.server.models
                "deprecated model names replaced: cleanup ${before.cleanup} -> ${after.cleanup}, " +
                    "command ${before.command} -> ${after.command}"
            }

            is Outcome.Err -> logger.warn(TAG, null) { "deprecated model names not replaced (${outcome.error.code})" }
        }
    }

    private companion object {
        val TAG = LogTag("Settings")
    }
}
