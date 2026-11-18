package net.jbglenn.telltype.platform.system

import android.content.ClipboardManager
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.domain.port.ClipboardPort

/** [ClipboardPort]: hand-off and Copy put the text on the clipboard, always flagged sensitive. */
internal class AndroidClipboard(
    private val clipboard: ClipboardManager,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : ClipboardPort {
    override suspend fun copy(text: String) {
        withContext(dispatchers.io) {
            try {
                clipboard.setPrimaryClip(SensitiveClip.of(text))
            } catch (e: SecurityException) {
                logger.warn(TAG, e) { "copy refused" }
            }
        }
    }

    private companion object {
        val TAG = LogTag("Clipboard")
    }
}
