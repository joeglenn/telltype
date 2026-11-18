package net.jbglenn.telltype

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.crash.CrashReporter
import net.jbglenn.telltype.data.work.DataWorkerFactory
import net.jbglenn.telltype.debug.DebugTools
import net.jbglenn.telltype.startup.AppStartup

/**
 * The application: installs the crash reporter, the debug-build checks (StrictMode, main-thread stall detector),
 * configures WorkManager with the data module's worker factory (the default initialiser is removed in the
 * manifest), and hands the rest to [AppStartup] in the process's scope.
 */
@HiltAndroidApp
class TelltypeApp :
    Application(),
    Configuration.Provider {
    @Inject
    internal lateinit var crashReporter: CrashReporter

    @Inject
    internal lateinit var startup: AppStartup

    @Inject
    internal lateinit var workerFactory: DataWorkerFactory

    @Inject
    internal lateinit var dispatchers: DispatcherProvider

    @Inject
    internal lateinit var logger: Logger

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        DebugTools.install(logger)
        startup.start(CoroutineScope(SupervisorJob() + dispatchers.main))
    }
}
