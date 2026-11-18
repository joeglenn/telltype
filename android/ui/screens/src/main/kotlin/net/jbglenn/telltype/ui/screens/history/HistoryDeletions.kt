package net.jbglenn.telltype.ui.screens.history

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.SessionId

/**
 * Deletions that can still be undone (D52). A swiped-away entry is only hidden at first; [HistoryEraser.delete]
 * removes it and its “That was wrong” reports when [UndoWindow] ends or [commit] is called, and [restore] brings
 * it back unchanged, in its place. A singleton with its own scope, so a deletion becomes final even when History is
 * left before the window ends. Every call and timer runs on the main dispatcher.
 */
@Singleton
class HistoryDeletions @Inject constructor(private val eraser: HistoryEraser, dispatchers: DispatcherProvider) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val timers = mutableMapOf<SessionId, Job>()
    private val hidden = MutableStateFlow<Set<SessionId>>(emptySet())

    /** Entries hidden from the lists while their deletion can be undone. */
    val pending: StateFlow<Set<SessionId>> = hidden.asStateFlow()

    /** Hides [id] and deletes it when [UndoWindow] has passed. */
    fun hide(id: SessionId) {
        timers.remove(id)?.cancel()
        hidden.update { it + id }
        timers[id] = scope.launch {
            delay(UndoWindow)
            timers.remove(id)
            erase(id)
        }
    }

    /** Brings [id] back; false when its deletion is already final or was never asked for. */
    fun restore(id: SessionId): Boolean {
        val timer = timers.remove(id) ?: return false
        timer.cancel()
        hidden.update { it - id }
        return true
    }

    /** Makes the deletion of [id] final now (the Undo bar went away early). */
    fun commit(id: SessionId) {
        val timer = timers.remove(id) ?: return
        timer.cancel()
        scope.launch { erase(id) }
    }

    /** Deletes [id]; when that fails the entry shows again rather than staying hidden while it still exists. */
    private suspend fun erase(id: SessionId) {
        eraser.delete(id)
        hidden.update { it - id }
    }

    /** Timing of the Undo bar. */
    companion object {
        /** How long a swipe can be undone. */
        val UndoWindow: Duration = 5.seconds
    }
}
