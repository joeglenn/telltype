package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.data.SnippetId
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionTimings

/**
 * `SaveHistory`, `EnqueuePendingJob`, `SaveCorrection` and the dictionary and snippet use counts. All writes go
 * through the persistence lane in the coordinator's scope: they keep their order, and `CancelWork` does not undo
 * what was already saved (section 10: a failure leaves the earlier results intact). Nothing is written for an
 * incognito field, and no history row when history is switched off.
 */
internal class PersistenceEffects(private val ctx: CoordinatorContext) {
    /** Writes the history row, adding start time, category and timings; a failure is logged only. */
    fun saveHistory(effect: SessionEffect.SaveHistory) {
        val historyOn = ctx.settings.privacy.historyOn
        if (historyOn && !effect.session.field.incognito) {
            val entry = historyEntry(effect)
            write("history upsert") { ctx.stores.history.upsert(entry) }
        }
    }

    /** Queues the recording with the session's category and tone; replies `QueueFailed` when it cannot. */
    fun enqueue(effect: SessionEffect.EnqueuePendingJob) {
        val style = ctx.sessions.recordFor(effect.session.id)?.style ?: ctx.styleFor(effect.session.field.app)
        val job = PendingJob(
            recording = effect.clip.recording,
            session = effect.session.id,
            createdAt = ctx.clock.now(),
            audioPath = effect.clip.path,
            category = style.category,
            tone = style.tone,
            attempts = 0,
            lastErrorCode = null,
            app = effect.session.field.app,
            incognito = effect.session.field.incognito,
        )
        ctx.persistenceLane.launch(ctx.work) {
            val failure: Outcome<Unit, StorageError> = Outcome.Err(StorageFailed)
            val result = ctx.calls.call("pending job enqueue", ctx.config.storage, failure) {
                ctx.stores.pendingJobs.enqueue(job)
            }
            if (result is Outcome.Err) ctx.reply(SessionEvent.QueueFailed(effect.session.id, result.error))
        }
    }

    /** Stores a learned replacement. */
    fun saveCorrection(correction: Correction) {
        write("learned replacement") {
            ctx.stores.dictionary.addReplacement(correction.heard, correction.replaceWith, EntrySource.LEARNED)
        }
    }

    /** Counts one use of every term in the final text, every applied replacement and every used snippet. */
    fun recordUses(inputs: TextInputs, processed: Processed, snippets: Set<SnippetId>) {
        if (!inputs.incognito) {
            val terms = inputs.terms.filter {
                processed.text.contains(it.term, ignoreCase = true)
            }.map { it.id }.toSet()
            if (terms.isNotEmpty() || processed.replacements.isNotEmpty()) {
                write("dictionary uses") { ctx.stores.dictionary.recordUses(terms, processed.replacements) }
            }
            snippets.forEach { id -> write("snippet use") { ctx.stores.snippets.recordUse(id) } }
        }
    }

    private fun write(name: String, block: suspend () -> Outcome<Any, StorageError>) {
        ctx.persistenceLane.launch(ctx.work) {
            val failure: Outcome<Any, StorageError> = Outcome.Err(StorageFailed)
            val result = ctx.calls.call(name, ctx.config.storage, failure, block = block)
            if (result is Outcome.Err) ctx.logger.warn(CoordinatorTag, null) { "$name failed: ${result.error.code}" }
        }
    }

    private fun historyEntry(effect: SessionEffect.SaveHistory): HistoryEntry {
        val session = effect.session
        val record = ctx.sessions.recordFor(session.id)
        return HistoryEntry(
            id = session.id,
            createdAt = record?.startedAt ?: ctx.clock.now(),
            app = session.field.app,
            category = (record?.style ?: ctx.styleFor(session.field.app)).category,
            mode = when {
                effect.text?.snippetOnly == true -> DictationMode.SNIPPET
                session.mode == BubbleMode.COMMAND -> DictationMode.COMMAND
                else -> DictationMode.DICTATE
            },
            finalText = effect.text?.text.orEmpty(),
            rawTranscript = effect.transcript?.text.orEmpty(),
            cleanupStatus = effect.text?.status,
            insertedVia = effect.insertedVia,
            audioDuration = effect.transcript?.audioDuration ?: record?.audioDuration ?: Duration.ZERO,
            timings = record?.timings ?: SessionTimings.None,
            errorCode = effect.error?.code,
        )
    }
}
