package net.jbglenn.telltype.platform.emulator

import android.Manifest
import android.app.ActivityManager
import android.media.AudioRecord
import android.os.Build
import android.provider.Settings
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.platform.mic.MicServiceState
import net.jbglenn.telltype.platform.mic.MicServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The emulator half of spike S2: with no activity of the app in front and without "Display over other apps", the
 * accessibility service starts `MicService` (foreground, type microphone), and an `AudioRecord` in the same process
 * then records without being silenced. Everything observed is written to logcat (tag A5Result) for the results file.
 *
 * Caveat recorded with the results: the process under test also runs the instrumentation, which the system ranks as
 * foreground-ish; the baseline probe without the service shows whether that alone already allows recording.
 */
class MicServiceTest {
    private val context = Emulator.instrumentation.targetContext

    @Before
    fun connect() {
        Emulator.connectService()
        Emulator.shell("pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}")
        Emulator.shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
    }

    @Test
    fun theServiceStartsInTheBackgroundAndRecordingIsNotSilenced() {
        Emulator.device.pressHome()
        Emulator.device.wait(Until.gone(By.pkg(Emulator.HOST)), WAIT_MS)
        Emulator.device.wait(Until.gone(By.pkg(context.packageName)), WAIT_MS)
        val resumed = Emulator.shell("dumpsys activity activities").lineSequence()
            .firstOrNull { "ResumedActivity" in it }?.trim().orEmpty()
        val overlays = Settings.canDrawOverlays(context)
        report("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) ${Build.FINGERPRINT}")
        report("foreground", resumed)
        report("display_over_other_apps", overlays.toString())
        report("process_importance_before", importance().toString())
        assertFalse("the test APK must not hold Display over other apps", overlays)
        assertFalse("no activity of the test APK in front", context.packageName in resumed)

        val baseline = AudioProbe.run()
        report("audiorecord_without_service", baseline.toString())

        val started = runBlocking { Emulator.graph.micService.start() }
        report("mic_service_start", started.toString())
        assertEquals(Outcome.Ok(Unit), started)
        val services = Emulator.shell("dumpsys activity services ${context.packageName}")
        val foreground = services.lineSequence().map { it.trim() }
            .filter { it.startsWith("isForeground=") || it.startsWith("foregroundServiceType") }.toList()
        report("mic_service_dumpsys", foreground.joinToString("; "))
        report("process_importance_with_service", importance().toString())
        assertTrue(foreground.toString(), foreground.any { "isForeground=true" in it })
        assertTrue(foreground.toString(), foreground.any { "0x00000080" in it })

        val withService = AudioProbe.run()
        report("audiorecord_with_service", withService.toString())
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, withService.recordingState)
        assertEquals(false, withService.silenced)

        runBlocking {
            Emulator.graph.micService.stop()
            withTimeout(10.seconds) { MicServiceStatus.shared.current.first { it == MicServiceState.Stopped } }
        }
        report("mic_service_stop", "stopped")
    }

    private fun importance(): Int = ActivityManager.RunningAppProcessInfo()
        .also(ActivityManager::getMyMemoryState).importance

    private fun report(key: String, value: String) = Emulator.record("s2", key, "-", value)

    private companion object {
        val WAIT_MS = 10.seconds.inWholeMilliseconds
    }
}
