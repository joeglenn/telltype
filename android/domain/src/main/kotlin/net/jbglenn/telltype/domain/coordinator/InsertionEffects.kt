package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.fold
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.InsertNotConfirmed
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent

/**
 * `Insert`, `ReplaceInserted`, `HandOff` and `CopyToClipboard` (docs/PLAN.md section 7.4). An insertion first waits
 * for the history writes queued before it, so the order of section 10 holds: audio on disk, transcript in History,
 * then insertion. A timeout or a thrown exception counts as `InsertNotConfirmed` (the reducer hands the text off).
 */
internal class InsertionEffects(private val ctx: CoordinatorContext) {
    /** Runs `Insert` in its session's job; replies `Inserted` or `InsertionFailed`. Measures insertion. */
    fun insert(effect: SessionEffect.Insert) {
        val (scope, record) = ctx.sessions.find(effect.session) ?: return ctx.dropped(effect)
        val earlierWrites = ctx.persistenceLane.last
        scope.launch {
            earlierWrites?.let { writes ->
                ctx.calls.call("history before insertion", ctx.config.storage, Unit) { writes.join() }
            }
            val started = ctx.clock.monotonic()
            val outcome = bounded("insertion") { ctx.ports.inserter.insert(effect.target, effect.text) }
            record.finishInsertion(started, ctx.clock.monotonic())
            ctx.reply(outcome.reply(effect.session))
        }
    }

    /** Runs `ReplaceInserted` (Raw, Undo) in its session's job; replies `Inserted` or `InsertionFailed`. */
    fun replace(effect: SessionEffect.ReplaceInserted) {
        val (scope, _) = ctx.sessions.find(effect.session) ?: return ctx.dropped(effect)
        scope.launch {
            val outcome = bounded("replacement") { ctx.ports.inserter.replace(effect.receipt, effect.text) }
            ctx.reply(outcome.reply(effect.session))
        }
    }

    /** Ladder step 4: the clipboard unless "Never use the clipboard" is on; replies `HandedOff`. */
    fun handOff(effect: SessionEffect.HandOff) {
        val (scope, record) = ctx.sessions.find(effect.session.id) ?: return ctx.dropped(effect)
        scope.launch {
            val started = ctx.clock.monotonic()
            val via = if (ctx.settings.insertion.neverUseClipboard) InsertedVia.NONE else copied(effect.text)
            record.finishInsertion(started, ctx.clock.monotonic())
            val receipt = InsertReceipt(effect.session.field, effect.text, replaced = "", range = null, via = via)
            ctx.reply(SessionEvent.HandedOff(effect.session.id, receipt))
        }
    }

    /** Copy tapped: the clipboard, in the coordinator's scope. */
    fun copy(text: String) {
        ctx.work.launch { copied(text) }
    }

    /** `HANDOFF` when the clipboard took [text], `NONE` when it timed out or failed (the text is still in History). */
    private suspend fun copied(text: String): InsertedVia =
        ctx.calls.call("clipboard copy", ctx.config.system, InsertedVia.NONE) {
            ctx.ports.clipboard.copy(text)
            InsertedVia.HANDOFF
        }

    private suspend fun bounded(
        name: String,
        block: suspend () -> Outcome<InsertReceipt, InsertionError>,
    ): Outcome<InsertReceipt, InsertionError> {
        val notConfirmed: Outcome<InsertReceipt, InsertionError> = Outcome.Err(InsertNotConfirmed)
        return ctx.calls.call(name, ctx.config.insertion, notConfirmed, block = block)
    }

    private fun Outcome<InsertReceipt, InsertionError>.reply(session: SessionId): SessionEvent = fold(
        onOk = { SessionEvent.Inserted(session, it) },
        onErr = { SessionEvent.InsertionFailed(session, it) },
    )
}
