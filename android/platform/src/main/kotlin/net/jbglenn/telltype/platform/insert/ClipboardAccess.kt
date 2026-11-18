package net.jbglenn.telltype.platform.insert

/**
 * The clipboard as ladder step 3 uses it. Implemented over `ClipboardManager` and by a fake in JVM tests.
 * No method throws.
 */
internal interface ClipboardAccess {
    /** Remembers what is on the clipboard now, so [restore] can put it back. */
    fun save(): SavedClip

    /** Puts [text] on the clipboard, flagged sensitive. False when the system refused. */
    fun putSensitive(text: String): Boolean

    /** Puts back what [save] saw; clears the clipboard when that was nothing or could not be read. */
    fun restore(saved: SavedClip)
}

/** An opaque clipboard content saved by [ClipboardAccess.save]. */
internal interface SavedClip
