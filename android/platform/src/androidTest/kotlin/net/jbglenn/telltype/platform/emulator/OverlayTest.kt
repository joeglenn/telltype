package net.jbglenn.telltype.platform.emulator

import android.Manifest
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.system.BubblePlacement
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The overlay host with real Compose content over the test host: an accessibility-overlay window that does not take
 * focus (keyboard stays up, key presses reach the field), sits above the keyboard docked to an edge without covering
 * the field, passes touches outside it through, and disappears over a system permission dialog.
 */
class OverlayTest {
    private val host get() = Emulator.graph.overlayHost

    @Before
    fun connect() {
        Emulator.connectService()
    }

    @After
    fun hide() {
        runBlocking { host.hide() }
    }

    @Test
    fun theBubbleFloatsAboveTheKeyboardWithoutTakingFocus() {
        val snapshot = InsertSteps.prepare(HostField.PLAIN, "Hello", TextRange(5, 5))

        assertEquals(Outcome.Ok(Unit), runBlocking { host.show(BubbleSide.RIGHT) { Bubble() } })
        val placed = awaitPlacement()
        val overlay = checkNotNull(overlayBounds()) { "no accessibility overlay window" }
        val keyboard = checkNotNull(snapshot.keyboardBounds)
        val field = snapshot.fieldBounds
        val screenWidth = Emulator.device.displayWidth

        assertTrue("bubble above the keyboard: $overlay vs $keyboard", overlay.bottom <= keyboard.top)
        assertFalse(
            "bubble does not cover the field: $overlay vs $field",
            Rect.intersects(overlay, Rect(field.left, field.top, field.right, field.bottom)),
        )
        assertTrue("docked right: $overlay", screenWidth - overlay.right in 0..EDGE_SLACK_PX)
        assertEquals(placed.x, overlay.left)
        Emulator.record("overlay", "plain", "-", "bubble=$overlay keyboardTop=${keyboard.top} field=$field")

        // The keyboard stays up and keys still reach the field: the overlay never takes input focus.
        val stillFocused = Emulator.awaitSnapshot { it?.target == snapshot.target && it.keyboardVisible }
        assertNotNull(stillFocused)
        Emulator.device.pressKeyCode(KeyEvent.KEYCODE_X)
        assertTrue(Emulator.device.wait(Until.hasObject(By.res(Emulator.HOST, "field").text("Hellox")), WAIT_MS))

        // A tap outside the bubble reaches the app below.
        Emulator.hostObject("tap_target").click()
        val counted = By.res(Emulator.HOST, "tap_target").text(Pattern.compile("taps: 1", Pattern.CASE_INSENSITIVE))
        assertTrue(Emulator.device.wait(Until.hasObject(counted), WAIT_MS))
        Emulator.record("overlay", "plain", "-", "keyboard up, key reached field, tap passed through")

        runBlocking { host.hide() }
        assertTrue("overlay window removed", overlayGone())
    }

    @Test
    fun theBubbleHidesOverAPermissionDialog() {
        Emulator.shell("pm revoke ${Emulator.HOST} ${Manifest.permission.CAMERA}")
        Emulator.shell("pm clear-permission-flags ${Emulator.HOST} ${Manifest.permission.CAMERA} user-set user-fixed")
        InsertSteps.prepare(HostField.PLAIN, "Hello", TextRange(5, 5))
        runBlocking { host.show(BubbleSide.RIGHT) { Bubble() } }
        awaitPlacement()

        Emulator.startHost("PermissionDialogActivity")
        val dialog = Emulator.device.wait(Until.findObject(By.pkg(PERMISSION_CONTROLLER)), WAIT_MS)
        assertNotNull("system permission dialog", dialog)

        assertNull(runBlocking { withTimeout(10.seconds) { host.placement.first { it == null } } })
        assertNull(Emulator.awaitSnapshot { it == null })
        assertTrue("no visible overlay over the dialog", overlayGone())
        Emulator.record("overlay", "permission_dialog", "-", "hidden, snapshot null")

        Emulator.device.findObject(By.res(PERMISSION_CONTROLLER, "permission_deny_button"))?.click()
    }

    /** The bounds of the visible accessibility-overlay window, or null. */
    private fun overlayBounds(): Rect? {
        val service = checkNotNull(AccessibilityServiceBridge.shared.service.value)
        val deadline = System.currentTimeMillis() + WINDOW_WAIT_MS
        var found: Rect? = null
        do {
            found = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                ?.let { window -> Rect().also(window::getBoundsInScreen) }
            if (found == null) Thread.sleep(POLL_MS)
        } while (found == null && System.currentTimeMillis() < deadline)
        return found
    }

    /** True once no accessibility-overlay window is listed (within [WINDOW_WAIT_MS]). */
    private fun overlayGone(): Boolean {
        val service = checkNotNull(AccessibilityServiceBridge.shared.service.value)
        val deadline = System.currentTimeMillis() + WINDOW_WAIT_MS
        var gone: Boolean
        do {
            gone = service.windows.none { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            if (!gone) Thread.sleep(POLL_MS)
        } while (!gone && System.currentTimeMillis() < deadline)
        return gone
    }

    private fun awaitPlacement(): BubblePlacement =
        runBlocking { withTimeout(10.seconds) { host.placement.first { it != null } } }!!

    private companion object {
        const val PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"
        const val EDGE_SLACK_PX = 64
        const val WINDOW_WAIT_MS = 3_000L
        const val POLL_MS = 100L
        val WAIT_MS = 10.seconds.inWholeMilliseconds
    }
}

/** A plain 56 dp square standing in for the real bubble (its Composable lives in a module :platform cannot use). */
@androidx.compose.runtime.Composable
private fun Bubble() {
    Layout(
        modifier = Modifier
            .semantics { testTag = "bubble" }
            .drawBehind { drawRect(Color(0xFF3355FF)) },
    ) { _, _ ->
        val bubbleDp = 56
        val size = (bubbleDp * Emulator.instrumentation.targetContext.resources.displayMetrics.density).toInt()
        layout(size, size) {}
    }
}
