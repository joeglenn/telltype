package net.jbglenn.telltype.startup

import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.jbglenn.telltype.assets.BundledAssets
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.data.work.PendingWorkScheduler
import net.jbglenn.telltype.logging.AppLogger
import net.jbglenn.telltype.runtime.DictationRuntime

/**
 * Everything the process starts once (docs/PLAN.md section 9), all off the main thread: parses the bundled
 * `shared/` files (a failure stops the app with a clear message), makes sure the retention and pending-job work is
 * scheduled, keeps the logger's verbose switch in step with Settings, replaces saved deprecated model aliases after
 * the next successful server check ([net.jbglenn.telltype.data.settings.ModelNameMigration]), recovers the
 * recordings a crash left behind and starts the [DictationRuntime], which runs the coordinator (and its own
 * recovery pass) whenever the accessibility service connects ([DictationStart]).
 */
internal class AppStartup @Inject constructor(
    private val assets: Provider<BundledAssets>,
    private val scheduler: Provider<PendingWorkScheduler>,
    private val settings: StartupSettings,
    private val logger: AppLogger,
    private val dictation: Provider<DictationStart>,
    private val dispatchers: DispatcherProvider,
) {
    /** Starts it all in [scope], the process's scope. */
    fun start(scope: CoroutineScope) {
        scope.launch(dispatchers.io) {
            val parsed = assets.get()
            logger.info(TAG) { "bundled prompts v${parsed.prompts.version}, guards v${parsed.guards.version}" }
            val work = scheduler.get()
            if (!work.scheduleRetention() || !work.schedulePendingJobs()) {
                logger.warn(TAG, null) { "background work could not be scheduled" }
            }
        }
        scope.launch(dispatchers.io) {
            settings.repository.get().settings.map { it.advanced.verboseLoggingUntil }.distinctUntilChanged().collect {
                logger.verboseUntil = it
            }
        }
        scope.launch(dispatchers.io) { settings.migration.get().run() }
        // Crash recovery, then the runtime; the runtime's graph (network clients, platform adapters) is built off
        // the main thread.
        scope.launch(dispatchers.io) { dictation.get().start(scope) }
    }

    private companion object {
        val TAG = LogTag("Startup")
    }
}
