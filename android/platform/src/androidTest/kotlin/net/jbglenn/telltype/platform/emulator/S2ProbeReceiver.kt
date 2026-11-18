package net.jbglenn.telltype.platform.emulator

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.platform.mic.MicServiceController
import net.jbglenn.telltype.platform.mic.MicServiceStatus
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * Spike S2 without instrumentation. Instrumentation ranks the test process as foreground, which hides the
 * background-microphone restriction, so this receiver repeats the probe in the process the system starts for the
 * accessibility service alone: `adb shell am broadcast -n <test package>/<this class>` after the test run, with the
 * service enabled and nothing of the app in front. It probes `AudioRecord` without and then with `MicService` (started
 * from the accessibility service) and logs everything under the tag `A5Result`.
 */
class S2ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        scope.launch {
            try {
                probe(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun probe(context: Context) {
        val bridge = AccessibilityServiceBridge.shared
        report("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        report("instrumented", "false")
        report("service_connected", (bridge.service.value != null).toString())
        report("display_over_other_apps", Settings.canDrawOverlays(context).toString())
        report("process_importance_before", importance().toString())
        report("audiorecord_without_service", AudioProbe.run().toString())
        val controller = MicServiceController(context, bridge, MicServiceStatus.shared, dispatchers, logger)
        report("mic_service_start", controller.start().toString())
        report("process_importance_with_service", importance().toString())
        report("audiorecord_with_service", AudioProbe.run().toString())
        controller.stop()
        report("mic_service_stop", MicServiceStatus.shared.current.value.toString())
    }

    private fun importance(): Int =
        ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance

    private fun report(key: String, value: String) {
        Log.i(TAG, "s2-uninstrumented|$key|-|$value")
    }

    private companion object {
        const val TAG = "A5Result"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Main
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val accessibility: CoroutineDispatcher =
                Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            override val audio: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        }
        val logger = object : Logger {
            override fun isEnabled(level: LogLevel): Boolean = true

            override fun log(level: LogLevel, tag: LogTag, throwable: Throwable?, message: () -> String) {
                Log.i(tag.name, message() + (throwable?.let { " ($it)" } ?: ""))
            }
        }
    }
}
