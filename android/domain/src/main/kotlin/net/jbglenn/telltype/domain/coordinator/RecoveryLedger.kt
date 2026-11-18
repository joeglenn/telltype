package net.jbglenn.telltype.domain.coordinator

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.jbglenn.telltype.core.model.RecordingId

/**
 * What crash recovery has done in this process (A7h). One instance per process: [CrashRecovery] runs at application
 * start and again whenever a coordinator starts, and the runs take turns through [exclusive], so a recording is
 * queued once. The retention job's orphan sweep deletes only recordings recovery has examined ([wasExamined]): a
 * recording left by a crash is never deleted before recovery has looked at it.
 */
public class RecoveryLedger {
    private val turn = Mutex()
    private val examined: MutableSet<RecordingId> = ConcurrentHashMap.newKeySet()

    /** Runs [block] while no other recovery run does. */
    public suspend fun <T> exclusive(block: suspend () -> T): T = turn.withLock { block() }

    /** Notes that recovery has looked at [recordings] (it queued them or could not). */
    public fun markExamined(recordings: Collection<RecordingId>) {
        examined += recordings
    }

    /** Whether recovery has looked at [recording] in this process. */
    public fun wasExamined(recording: RecordingId): Boolean = recording in examined
}
