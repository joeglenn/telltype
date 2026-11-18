package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.domain.text.diffCorrection

/**
 * `WatchFieldEdits`: the learning window (docs/PLAN.md section 4.5). Watches the field of the last dictation for
 * `learningWindow`, runs `diffCorrection` on each text and reports each new correction: `CorrectionDetected` (the
 * reducer offers "Learn X → Y"), or `LearnAccepted` directly when learning is automatic. Not when learning is off
 * or the field is incognito. One watch at a time; a new dictation ends it.
 */
internal class LearningWatch(private val ctx: CoordinatorContext) {
    private var watch: Job? = null

    /** Starts watching [receipt]'s field, replacing any earlier watch. */
    fun watch(receipt: InsertReceipt) {
        stop()
        val mode = ctx.settings.dictionary.learnFromCorrections
        if (mode != LearnMode.OFF && !receipt.target.incognito) {
            watch = ctx.work.launch { collect(receipt, mode) }
        }
    }

    /** Ends the current watch. */
    fun stop() {
        watch?.cancel()
        watch = null
    }

    private suspend fun collect(receipt: InsertReceipt, mode: LearnMode) {
        val window = ctx.config.learningWindow
        var last: Correction? = null
        ctx.calls.call("edit watch", window + ctx.config.system, Unit) {
            ctx.ports.editWatcher.edits(receipt.target, window).collect { text ->
                val correction = ctx.onDefault { diffCorrection(receipt, text) }
                if (correction != null && correction != last) {
                    last = correction
                    val event = if (mode == LearnMode.AUTOMATIC) {
                        SessionEvent.LearnAccepted(correction)
                    } else {
                        SessionEvent.CorrectionDetected(correction)
                    }
                    ctx.reply(event)
                }
            }
        }
    }
}
