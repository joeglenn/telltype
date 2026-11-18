package net.jbglenn.telltype.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import net.jbglenn.telltype.audio.store.StorageOverride

/**
 * Debug builds: a full disk on demand, from lines of the faults file `no_backup/debug/fake-faults` (written by the
 * connected tests): [FULL] makes the recording store see no free space; `storage=fills-after=<bytes>` lets each new
 * recording write that many bytes of audio before its writes fail with `ENOSPC`. Read on every call, on the I/O
 * dispatcher (the store and the recorder call it there).
 */
internal class DebugStorageOverride @Inject constructor(@ApplicationContext private val context: Context) :
    StorageOverride {
    override fun full(): Boolean = FULL in lines()

    override fun bytesBeforeFull(): Long? =
        lines().firstNotNullOfOrNull { it.removePrefix(FILLS_AFTER).takeIf { _ -> it.startsWith(FILLS_AFTER) } }
            ?.toLongOrNull()

    private fun lines(): List<String> = File(context.noBackupFilesDir, FakeFaults.FAULTS_MARKER)
        .takeIf(File::isFile)?.readLines()?.map(String::trim).orEmpty()

    companion object {
        /** The disk is full from the start. */
        const val FULL = "storage=full"

        /** Prefix of the line that lets a recording write `<bytes>` before the disk is full. */
        const val FILLS_AFTER = "storage=fills-after="
    }
}
