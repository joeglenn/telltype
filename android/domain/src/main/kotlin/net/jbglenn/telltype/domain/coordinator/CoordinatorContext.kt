package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.debug
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.domain.session.resolveCategory

/** The coordinator's log tag. */
internal val CoordinatorTag: LogTag = LogTag("SessionCoordinator")

/**
 * What every effect handler shares. Confined to the coordinator's dispatcher (`main`): the loop and every effect
 * coroutine run there, so the mutable fields need no lock.
 *
 * @property work the scope of all effect work (session jobs, lanes, timers, watchers); a child of the coordinator.
 */
internal class CoordinatorContext(
    val ports: CoordinatorPorts,
    val stores: CoordinatorStores,
    val environment: CoordinatorEnvironment,
    val work: CoroutineScope,
    private val inbox: SendChannel<Inbound>,
    private val currentState: () -> SessionState,
) {
    val config: CoordinatorConfig get() = environment.config
    val logger: Logger get() = environment.logger
    val clock: Clock get() = environment.clock
    val calls: PortCalls = PortCalls(environment.logger)
    val sessions: SessionScopes = SessionScopes(work)

    /** Recorder operations, strictly one after another. */
    val captureLane: SerialLane = SerialLane()

    /** Repository writes, strictly one after another. */
    val persistenceLane: SerialLane = SerialLane()

    /** The latest settings, kept current by a collector. */
    var settings: Settings = Settings.Defaults

    /** The latest app rules, kept current by a collector. */
    var appRules: List<AppRule> = emptyList()

    /** The state the last transition produced. */
    val state: SessionState get() = currentState()

    /** Hands [inbound] to the event loop (suspends while the inbox is full; never drops a reply). */
    suspend fun send(inbound: Inbound) {
        inbox.send(inbound)
    }

    /** Hands an effect's reply to the event loop. */
    suspend fun reply(event: SessionEvent) {
        send(Inbound.External(event))
    }

    /** Category from the app rule and the shipped map; tone from the rule's override or the category's setting. */
    fun styleFor(app: AppPackage): SessionStyle {
        val rule = appRules.firstOrNull { it.app == app }
        val category = resolveCategory(app, rule)
        return SessionStyle(category, rule?.toneOverride ?: settings.styles.toneFor(category))
    }

    /** The current content of a repository [flow], or [fallback] when it cannot be read in time. */
    suspend fun <T : Any> firstOf(name: String, flow: Flow<T>, fallback: T): T =
        calls.call(name, config.storage, fallback) { flow.first() }

    /** Runs CPU-bound text work on the default dispatcher. */
    suspend fun <T> onDefault(block: () -> T): T = withContext(environment.dispatchers.default) { block() }

    /** An effect for a session that is no longer current (it raced with a cancel) does nothing. */
    fun dropped(effect: SessionEffect) {
        logger.debug(CoordinatorTag) { "${effect::class.simpleName} for a finished session dropped" }
    }
}
