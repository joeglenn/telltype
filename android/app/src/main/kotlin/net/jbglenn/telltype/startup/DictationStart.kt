package net.jbglenn.telltype.startup

import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.domain.coordinator.CrashRecovery
import net.jbglenn.telltype.runtime.DictationRuntime

/**
 * Recovers the recordings a crash left behind, then starts the [DictationRuntime]. Recovery runs here, at every
 * application start (A7h), and not only when the accessibility service connects: with the service off a recording
 * would otherwise wait unrecovered while the retention job sweeps. It runs before the runtime, so no session of
 * this process exists yet and a live recording is never taken for an orphan.
 */
internal class DictationStart @Inject constructor(
    private val recovery: Provider<CrashRecovery>,
    private val runtime: Provider<DictationRuntime>,
    private val dispatchers: DispatcherProvider,
) {
    /** Recovers, then starts the runtime in [scope] on the main thread. Call off the main thread. */
    suspend fun start(scope: CoroutineScope) {
        recovery.get().run()
        val dictation = runtime.get()
        withContext(dispatchers.main) { dictation.start(scope) }
    }
}
