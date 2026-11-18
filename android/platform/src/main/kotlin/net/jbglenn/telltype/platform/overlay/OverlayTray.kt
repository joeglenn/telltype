package net.jbglenn.telltype.platform.overlay

import android.view.WindowManager
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.AccessibilityDisabled
import net.jbglenn.telltype.core.model.error.PermissionError
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * A second accessibility-overlay window across the bottom of the screen, for the snooze band shown while the bubble
 * is dragged (docs/PLAN.md section 5.2). Like the bubble's window it is not focusable and sized to its content; it
 * is anchored to the bottom edge from the start and shown once measured. The content comes from `:ui:overlay`
 * through `:app`.
 *
 * [show] and [hide] switch to the main thread. The owner hides it when the drag ends and when the accessibility
 * service disconnects.
 */
public class OverlayTray internal constructor(
    private val bridge: AccessibilityServiceBridge,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    private var window: OverlayWindow? = null

    /** Shows [content] on the bottom edge, or replaces the content already shown. Fails while disconnected. */
    public suspend fun show(content: @Composable () -> Unit): Outcome<Unit, PermissionError> =
        withContext(dispatchers.main) {
            val shown = window?.also { it.setContent(content) } ?: open(content)
            if (shown == null) Outcome.Err(AccessibilityDisabled) else Outcome.Ok(Unit)
        }

    /** Removes the window, if any. */
    public suspend fun hide() {
        withContext(dispatchers.main) {
            window?.detach()
            window = null
        }
    }

    private fun open(content: @Composable () -> Unit): OverlayWindow? {
        val connected = bridge.service.value ?: return null
        var created: OverlayWindow? = null
        created = OverlayWindow(
            connected,
            content,
            onResized = { created?.let(::reveal) },
            title = OverlayWindow.TRAY_TITLE,
            bottomEdge = true,
        )
        return try {
            created.attach()
            window = created
            created
        } catch (e: WindowManager.BadTokenException) {
            logger.warn(TAG, e) { "tray window refused" }
            null
        } catch (e: IllegalStateException) {
            logger.warn(TAG, e) { "tray window refused" }
            null
        }
    }

    /** Shows the tray once it has a size; its window sits on the bottom edge by its gravity, so no move is needed. */
    private fun reveal(tray: OverlayWindow) {
        if (tray.height > 0) tray.setShown(true)
    }

    private companion object {
        val TAG = LogTag("OverlayTray")
    }
}
