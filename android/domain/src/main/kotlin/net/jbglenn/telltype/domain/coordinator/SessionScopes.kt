package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.session.Session
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * One child job per dictation (docs/PLAN.md section 9). The session's job owns every effect of that session (start,
 * stop, transcription, cleanup, insertion), so `CancelWork` cancels them together. When the state no longer belongs
 * to the session, its job is completed (work already running, such as an Undo, may finish within its timeout);
 * when a new session starts, every older session job is cancelled, so nothing of an old session can touch a new
 * one. Confined to the coordinator's dispatcher.
 */
internal class SessionScopes(private val work: CoroutineScope) {
    private class Owned(val record: SessionRecord, val job: CompletableJob, val scope: CoroutineScope)

    private var current: Owned? = null
    private val retiring = mutableListOf<Job>()

    /** The session whose recording is being opened while the state is Arming; null otherwise. */
    var pendingStart: SessionId? = null
        private set

    /** Opens the job of a new session, after cancelling every older one. */
    fun open(record: SessionRecord): CoroutineScope {
        cancelAll()
        val job = SupervisorJob(work.coroutineContext.job)
        val owned = Owned(record, job, CoroutineScope(work.coroutineContext + job))
        current = owned
        pendingStart = record.id
        return owned.scope
    }

    /** The scope and record of session [id] while it is the current session. */
    fun find(id: SessionId): Pair<CoroutineScope, SessionRecord>? =
        current?.takeIf { it.record.id == id }?.let { it.scope to it.record }

    /** The record of session [id] while it is the current session. */
    fun recordFor(id: SessionId): SessionRecord? = current?.record?.takeIf { it.id == id }

    /** Whether [id] is the start the bubble is arming for. */
    fun isPendingStart(id: SessionId): Boolean = pendingStart == id

    /** `CancelWork`: cancels everything session [id] runs. */
    fun cancel(id: SessionId) {
        current?.takeIf { it.record.id == id }?.job?.cancel()
    }

    /** `AbortRecordingStart`: cancels the pending start's session and returns its record. */
    fun abortStart(): SessionRecord? {
        val owned = current?.takeIf { it.record.id == pendingStart }
        pendingStart = null
        owned?.job?.cancel()
        return owned?.record
    }

    /** After each transition: a session the state no longer belongs to is completed and retired. */
    fun settle(state: SessionState) {
        if (state !is SessionState.Arming) pendingStart = null
        val live = state.owner?.id ?: pendingStart
        val owned = current
        if (owned != null && owned.record.id != live) {
            owned.job.complete()
            retiring += owned.job
            current = null
        }
        retiring.removeAll { it.isCompleted }
    }

    private fun cancelAll() {
        current?.job?.cancel()
        retiring.forEach { it.cancel() }
        retiring.clear()
        current = null
    }
}

/** The session a state belongs to, null for the idle states and Arming. */
internal val SessionState.owner: Session?
    get() = when (this) {
        is SessionState.Listening -> session
        is SessionState.Working -> session
        is SessionState.Done -> session
        is SessionState.Problem -> session
        SessionState.Hidden, is SessionState.Ready, is SessionState.Unavailable, is SessionState.Arming -> null
    }
