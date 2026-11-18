package net.jbglenn.telltype.platform.insert

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.FieldChanged
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.FieldProtected
import net.jbglenn.telltype.core.model.error.InsertNotConfirmed
import net.jbglenn.telltype.core.model.error.InsertionError
import net.jbglenn.telltype.platform.field.FieldAccess
import net.jbglenn.telltype.platform.field.FieldState

/**
 * The decision logic of the insertion ladder (docs/PLAN.md section 7.4): which step runs, when it counts as
 * confirmed, and when the ladder stops. Pure apart from [FieldAccess] and [ClipboardAccess]; run it on the
 * accessibility thread.
 *
 * Rules:
 * - The field must exist ([FieldGone]), keep its focus for a new insertion ([FieldChanged]), take dictation
 *   ([FieldProtected]) and, for Raw and Undo, still hold the text being replaced ([FieldChanged]).
 * - Nothing is attempted when no attempt could be verified (no readable text and no selection).
 * - Every attempt is confirmed by reading the field back within [LadderConfig.stepTimeout]; an attempt that is not
 *   confirmed passes on to the next step.
 * - A relative step (input connection, paste) runs after an earlier attempt only when the field's text is still
 *   exactly what it was, so text is never inserted twice. Set-text writes the whole expected text, so it may run.
 * - The paste step restores the previous clipboard whatever happens.
 * - A step whose exact result does not show within the timeout still counts as confirmed when the field holds our
 *   text as it adapted it (auto-capitalised or filtered, [Expectation.adapted]): a later step would be adapted the
 *   same way, and handing the text off as well would offer it twice.
 */
internal class InsertionLadder(private val clipboard: ClipboardAccess, private val config: LadderConfig) {
    /** Inserts [request] into [field]. */
    suspend fun run(field: FieldAccess, request: InsertRequest): Outcome<Inserted, InsertionError> {
        val initial = field.read()
        val refusal = when {
            initial == null -> FieldGone
            request.requireFocus && !initial.focused -> FieldChanged
            !initial.kind.acceptsDictation -> FieldProtected
            request.expected != null && !initial.holds(request) -> FieldChanged
            else -> null
        }
        val plan = if (initial != null && refusal == null) InsertionPlan.of(initial, request) else null
        return when {
            refusal != null -> Outcome.Err(refusal)
            plan == null -> Outcome.Err(InsertNotConfirmed)
            else -> climb(field, plan)
        }
    }

    private suspend fun climb(field: FieldAccess, plan: InsertionPlan): Outcome<Inserted, InsertionError> {
        var touched = false
        var result: Attempt = Attempt.NotSent
        val steps = config.steps.iterator()
        while (steps.hasNext() && !result.decisive) {
            val step = steps.next()
            if (mayRun(step, plan, touched, field)) {
                result = attempt(step, field, plan)
                touched = touched || result == Attempt.Unconfirmed
            }
        }
        return when (result) {
            is Attempt.Confirmed -> Outcome.Ok(result.inserted)
            Attempt.Gone -> Outcome.Err(FieldGone)
            Attempt.NotSent, Attempt.Unconfirmed -> Outcome.Err(InsertNotConfirmed)
        }
    }

    /** [step] applies to [plan], and after an attempt that may have changed the field it cannot insert twice. */
    private fun mayRun(step: LadderStep, plan: InsertionPlan, touched: Boolean, field: FieldAccess): Boolean {
        val safe = !touched || !step.relative || unchanged(field, plan)
        return applies(step, plan) && safe
    }

    private fun applies(step: LadderStep, plan: InsertionPlan): Boolean = when (step) {
        LadderStep.INPUT_CONNECTION -> plan.initial.focused
        LadderStep.SET_TEXT -> plan.expectation is Expectation.FullText && !plan.initial.showingHint
        LadderStep.PASTE -> plan.allowClipboard && plan.text.isNotEmpty()
    }

    /** The text is still the initial one (and, without a known range, so is the selection). */
    private fun unchanged(field: FieldAccess, plan: InsertionPlan): Boolean {
        val now = field.read() ?: return false
        val sameText = now.content != null && now.content == plan.initial.content
        return sameText && (plan.range != null || now.selection == plan.initial.selection)
    }

    private suspend fun attempt(step: LadderStep, field: FieldAccess, plan: InsertionPlan): Attempt = when (step) {
        LadderStep.INPUT_CONNECTION ->
            if (field.commitText(plan.range, plan.text)) confirm(step, field, plan) else Attempt.NotSent

        LadderStep.SET_TEXT -> {
            val full = plan.expectation as Expectation.FullText
            if (field.setText(full.value, full.inserted.end)) confirm(step, field, plan) else Attempt.NotSent
        }

        LadderStep.PASTE -> paste(field, plan)
    }

    private suspend fun paste(field: FieldAccess, plan: InsertionPlan): Attempt {
        val saved = clipboard.save()
        return try {
            if (clipboard.putSensitive(plan.text) && field.paste(plan.range)) {
                confirm(LadderStep.PASTE, field, plan)
            } else {
                Attempt.NotSent
            }
        } finally {
            clipboard.restore(saved)
        }
    }

    private suspend fun confirm(step: LadderStep, field: FieldAccess, plan: InsertionPlan): Attempt =
        withTimeoutOrNull(config.stepTimeout) {
            var verdict: Attempt? = null
            while (verdict == null) {
                verdict = when (val state = field.read()) {
                    null -> Attempt.Gone

                    else -> plan.expectation.match(state, plan.text)?.let {
                        Attempt.Confirmed(Inserted(step.via, it.inserted, it.replaced))
                    }
                }
                if (verdict == null) delay(config.pollInterval)
            }
            verdict
        } ?: adapted(step, field, plan) ?: Attempt.Unconfirmed

    /** After [step]'s timeout: the field holds the text as it adapted it, or is gone; null otherwise. */
    private fun adapted(step: LadderStep, field: FieldAccess, plan: InsertionPlan): Attempt? {
        val state = field.read() ?: return Attempt.Gone
        return plan.expectation.adapted(state, plan.text)?.let { located ->
            val landed = state.content?.substring(located.inserted.start, located.inserted.end)
            Attempt.Confirmed(Inserted(step.via, located.inserted, located.replaced, landed))
        }
    }

    private fun FieldState.holds(request: InsertRequest): Boolean {
        val value = content
        val range = request.range
        return value != null && range != null && range.end <= value.length &&
            value.substring(range.start, range.end) == request.expected
    }

    /** The result of one step. */
    private sealed interface Attempt {
        /** The ladder stops here: confirmed, or the field is gone. */
        val decisive: Boolean get() = this is Confirmed || this == Gone

        /** The step did not act (not available or refused), so the field is as it was. */
        data object NotSent : Attempt

        /** The step acted but reading back did not confirm it in time; the field may have changed. */
        data object Unconfirmed : Attempt

        /** The field disappeared. */
        data object Gone : Attempt

        /** Read back and confirmed. */
        data class Confirmed(val inserted: Inserted) : Attempt
    }
}
