package net.jbglenn.telltype.debug

import javax.inject.Inject

/**
 * Debug builds: failures the fake engines act out, one per line in the marker file `no_backup/debug/fake-faults`
 * (written by the connected tests), read on every call. No file means no faults.
 */
internal class FakeFaults @Inject constructor(private val switches: DebugSwitches) {
    /** Whether the faults file contains the line [fault]. */
    suspend fun has(fault: String): Boolean =
        switches.markerText(FAULTS_MARKER)?.lineSequence()?.any { it.trim() == fault } == true

    companion object {
        /** The faults file, relative to `noBackupFilesDir`. */
        const val FAULTS_MARKER = "debug/fake-faults"

        /** The speech engine answers `EmptyTranscript`. */
        const val STT_EMPTY = "stt=empty"

        /** The cleaner answers long after any deadline. */
        const val CLEANUP_SLOW = "cleanup=slow"

        /** The cleaner drops a leading filler, so the cleaned text differs from the transcript. */
        const val CLEANUP_REWRITE = "cleanup=rewrite"
    }
}
