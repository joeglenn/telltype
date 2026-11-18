package net.jbglenn.telltype.testhost

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test for the UI Automator wiring: the host app starts and shows its title. */
@RunWith(AndroidJUnit4::class)
class TestHostLaunchTest {
    @Test
    fun launchesAndShowsTitle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
            "No launcher activity in ${context.packageName}"
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        context.startActivity(launch)

        val title = device.wait(Until.findObject(By.res(context.packageName, "title")), LAUNCH_TIMEOUT_MS)
        assertNotNull("Test host title did not appear within $LAUNCH_TIMEOUT_MS ms", title)
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MS = 5_000L
    }
}
