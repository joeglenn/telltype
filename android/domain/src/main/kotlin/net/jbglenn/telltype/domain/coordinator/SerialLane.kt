package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs jobs one after another in the order they were launched, each in the scope the caller chooses (a session's
 * scope or the coordinator's). Used for the recorder (start, stop, cancel and delete never overlap) and for
 * persistence (two writes of the same history row land in order). Confined to the coordinator's dispatcher.
 */
internal class SerialLane {
    private var tail: Job? = null

    /** The job launched last, or null; joining it waits for everything launched so far. */
    val last: Job? get() = tail

    /** Launches [block] in [scope] to run after every job launched before it has completed. */
    fun launch(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        val previous = tail
        val job = scope.launch {
            previous?.join()
            block()
        }
        tail = job
        return job
    }
}
