package net.jbglenn.telltype.audio.store

import android.content.Context
import android.os.storage.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Free space where recordings are written. A seam so tests can simulate a full disk. */
internal fun interface StorageSpace {
    /** Bytes this app can still write in [directory], or null when the system cannot say. */
    fun usableBytes(directory: File): Long?
}

/**
 * The system's figure (`StorageManager.getAllocatableBytes`): free space plus cached data the system would clear
 * for us, so the store does not delete retained audio while the system could make room by itself.
 */
internal class SystemStorageSpace @Inject constructor(@ApplicationContext private val context: Context) :
    StorageSpace {
    override fun usableBytes(directory: File): Long? {
        val storage = context.getSystemService(StorageManager::class.java)
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(directory))
        } catch (_: IOException) {
            null
        }
    }
}

/** True when [this] (or a cause) reports that the disk is full (`ENOSPC`). */
internal fun IOException.isNoSpace(): Boolean = generateSequence<Throwable>(this) { it.cause }
    .mapNotNull { it.message }
    .any { message -> NO_SPACE_MARKERS.any { message.contains(it, ignoreCase = true) } }

/** How Linux and Android word `ENOSPC` in exception messages. */
private val NO_SPACE_MARKERS = listOf("ENOSPC", "No space left on device")
