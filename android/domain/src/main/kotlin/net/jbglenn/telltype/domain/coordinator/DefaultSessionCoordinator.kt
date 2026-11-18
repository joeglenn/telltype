package net.jbglenn.telltype.domain.coordinator

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.debug
import net.jbglenn.telltype.core.common.error
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.domain.session.reduce

/**
 * The [SessionCoordinator] (work package A7a). Construct one per accessibility-service lifetime, call [start] when
 * the service connects and [close] when it is destroyed. The event loop and every effect run on
 * `CoordinatorEnvironment.dispatchers.main`; [dispatch] may be called from any thread.
 *
 * Structure (docs/PLAN.md section 9): one supervisor job for the coordinator with two children, the daemons (event
 * loop, focus, settings, app-rule and interruption feeds) and the work (one child job per dictation, the timers,
 * the learning watch, the capture and persistence lanes). [close] cancels the supervisor; afterwards nothing runs.
 */
public class DefaultSessionCoordinator(
    ports: CoordinatorPorts,
    private val stores: CoordinatorStores,
    private val focusSources: FocusSources,
    private val environment: CoordinatorEnvironment,
) : SessionCoordinator {
    private val job: CompletableJob = SupervisorJob()
    private val failures = CoroutineExceptionHandler { _, failure ->
        environment.logger.error(CoordinatorTag, null) { "uncaught ${failure::class.simpleName}" }
    }
    private val root =
        CoroutineScope(job + environment.dispatchers.main + failures + CoroutineName("SessionCoordinator"))
    private val daemons = CoroutineScope(root.coroutineContext + SupervisorJob(job))
    private val workJob: CompletableJob = SupervisorJob(job)
    private val work = CoroutineScope(root.coroutineContext + workJob)

    // Bounded: overlay gestures and effect replies are few per second; replies suspend while it is full and are
    // never lost, dispatch drops and logs (a full inbox means the loop is stuck, so more gestures would not help).
    private val inbox = Channel<Inbound>(environment.config.inboxCapacity)
    private val stateFlow = MutableStateFlow<SessionState>(SessionState.Hidden)
    private val chipFlow =
        MutableSharedFlow<Chip>(extraBufferCapacity = CHIP_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val context = CoordinatorContext(ports, stores, environment, work, inbox) { stateFlow.value }
    private val runner = EffectRunner(context, chipFlow)
    private val recovery = CrashRecovery(
        RecoveryStores(ports.recordings, stores.pendingJobs, stores.settings),
        ports.notifier,
        stores.recoveryLedger,
        environment.clock,
        environment.logger,
        environment.config,
    )
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    override val state: StateFlow<SessionState> = stateFlow.asStateFlow()

    override val chips: Flow<Chip> = chipFlow.asSharedFlow()

    /** The coordinator's supervisor job; completed once [close] has finished tearing down. */
    internal val lifecycle: Job get() = job

    override fun dispatch(event: SessionEvent) {
        if (!closed.get()) {
            val sent = inbox.trySend(Inbound.External(event))
            if (sent.isFailure && !sent.isClosed) {
                environment.logger.warn(CoordinatorTag, null) { "inbox full, ${event::class.simpleName} dropped" }
            }
        }
    }

    override fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) {
            daemons.launch {
                stores.settings.settings.catch { feedFailed("settings") }.collect { context.settings = it }
            }
            daemons.launch { stores.appRules.rules.catch { feedFailed("app rules") }.collect { context.appRules = it } }
            daemons.launch { holdUntilClosed() }
            daemons.launch { recoverThenLoop() }
            daemons.launch {
                FocusFeed(focusSources, stores, environment.clock).focus()
                    .catch { feedFailed("focus") }
                    .collect { context.send(Inbound.External(SessionEvent.FocusChanged(it))) }
            }
            daemons.launch {
                context.ports.audio.interruptions
                    .catch { feedFailed("interruptions") }
                    .collect { context.send(Inbound.External(SessionEvent.Interrupted(it))) }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) job.cancel()
    }

    /** Active effect coroutines (session work, lanes, timers, watchers); 0 when nothing of any session runs. */
    internal fun runningWork(): Int = workJob.activeWork()

    private suspend fun recoverThenLoop() {
        val orphans = recovery.findOrphans()
        work.launch { recovery.recover(orphans) }
        for (inbound in inbox) step(inbound)
    }

    /** One event, start to finish, before the next is taken: reduce, publish, run the effects, settle. */
    private fun step(inbound: Inbound) {
        val event = runner.accept(inbound)
        val before = stateFlow.value
        val transition = event?.let { next ->
            runCatching { reduce(before, next) }
                .onFailure {
                    environment.logger.error(CoordinatorTag, null) { "reducer threw ${it::class.simpleName}" }
                }
                .getOrNull()
        }
        if (event == null) {
            environment.logger.debug(CoordinatorTag) { "stale ${inbound::class.simpleName} dropped" }
        } else if (transition != null) {
            stateFlow.value = transition.state
            sessionEndLine(event, before, transition.state)?.let { line ->
                environment.logger.info(CoordinatorTag) { line }
            }
            environment.logger.debug(CoordinatorTag) {
                "${event::class.simpleName}: ${before::class.simpleName} -> ${transition.state::class.simpleName} " +
                    transition.effects.joinToString(prefix = "[", postfix = "]") { it::class.simpleName.orEmpty() }
            }
            transition.effects.forEach { effect ->
                context.calls.quietly(effect::class.simpleName.orEmpty()) { runner.run(effect) }
            }
            runner.settle(transition.state)
        }
    }

    private suspend fun holdUntilClosed() {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(environment.config.teardown) { runner.teardown(stateFlow.value) }
            }
        }
    }

    private fun feedFailed(name: String) {
        environment.logger.error(CoordinatorTag, null) { "$name feed failed" }
    }

    private companion object {
        const val CHIP_BUFFER = 8
    }
}

/** Active coroutines below this job; supervisor jobs (session jobs) only group them and are not counted. */
private fun Job.activeWork(): Int = children.sumOf { child ->
    when {
        child is CompletableJob -> child.activeWork()
        child.isActive -> 1
        else -> 0
    }
}
