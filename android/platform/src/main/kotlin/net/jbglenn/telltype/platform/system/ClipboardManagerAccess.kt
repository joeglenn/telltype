package net.jbglenn.telltype.platform.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.platform.insert.ClipboardAccess
import net.jbglenn.telltype.platform.insert.SavedClip

/** Builds the clips the app puts on the clipboard: plain text, always flagged sensitive (kept out of previews). */
internal object SensitiveClip {
    private const val LABEL = "Telltype"

    /** A plain-text clip of [text] with `EXTRA_IS_SENSITIVE`. */
    fun of(text: String): ClipData = ClipData.newPlainText(LABEL, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
}

/**
 * [ClipboardAccess] over `ClipboardManager`, for ladder step 3.
 *
 * Android 10 and later let only the focused app and the default keyboard read the clipboard, so while another app
 * is in front [save] usually sees nothing: `getPrimaryClip` answers null both for "empty" and for "not allowed".
 * [restore] then clears the clipboard, so the dictated text does not stay there; the user's earlier clip is lost
 * either way, as it would be by the hand-off step.
 */
internal class ClipboardManagerAccess(private val clipboard: ClipboardManager, private val logger: Logger) :
    ClipboardAccess {
    override fun save(): SavedClip = guarded(Saved(null)) { Saved(clipboard.primaryClip) }

    override fun putSensitive(text: String): Boolean = guarded(false) {
        clipboard.setPrimaryClip(SensitiveClip.of(text))
        true
    }

    override fun restore(saved: SavedClip) {
        val clip = (saved as? Saved)?.clip
        guarded(Unit) { if (clip != null) clipboard.setPrimaryClip(clip) else clipboard.clearPrimaryClip() }
    }

    private fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        logger.warn(TAG, e) { "clipboard refused" }
        fallback
    }

    /** What was on the clipboard; null when it was empty or unreadable. */
    private class Saved(val clip: ClipData?) : SavedClip {
        override fun toString(): String = "SavedClip(readable=${clip != null})"
    }

    private companion object {
        val TAG = LogTag("Clipboard")
    }
}
