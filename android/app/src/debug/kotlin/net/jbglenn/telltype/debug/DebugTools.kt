package net.jbglenn.telltype.debug

import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn

/** Debug builds only: StrictMode and a main-thread stall detector (docs/PLAN.md section 9). */
internal object DebugTools {
    private val TAG = LogTag("DebugTools")
    private const val PING_MS = 500L
    private const val STALL_MS = 1_000L

    /** Crashes on disk or network access from the main thread; logs leaks; starts the stall detector. */
    fun install(logger: Logger) {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .penaltyDeath()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectActivityLeaks()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
        startStallDetector(logger)
    }

    /**
     * A daemon thread pings the main looper every [PING_MS]; when a ping has waited longer than [STALL_MS] it logs
     * the main thread's stack once per stall (class and method names only).
     */
    private fun startStallDetector(logger: Logger) {
        val main = Handler(Looper.getMainLooper())
        val answered = AtomicLong(SystemClock.uptimeMillis())
        Thread({
            var sent = SystemClock.uptimeMillis()
            var reported = false
            main.post { answered.set(SystemClock.uptimeMillis()) }
            while (true) {
                SystemClock.sleep(PING_MS)
                val now = SystemClock.uptimeMillis()
                if (answered.get() >= sent) {
                    // Answered: send the next ping (one at a time, so a blocked looper does not pile them up).
                    reported = false
                    sent = now
                    main.post { answered.set(SystemClock.uptimeMillis()) }
                } else if (now - sent >= STALL_MS && !reported) {
                    reported = true
                    val stack = Looper.getMainLooper().thread.stackTrace.take(STACK_LINES).joinToString("\n  at ")
                    logger.warn(TAG, null) { "main thread stalled over ${now - sent} ms:\n  at $stack" }
                }
            }
        }, "telltype-stall-detector").apply { isDaemon = true }.start()
    }

    private const val STACK_LINES = 12
}
