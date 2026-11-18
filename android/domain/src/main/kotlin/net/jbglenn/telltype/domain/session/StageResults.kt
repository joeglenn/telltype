package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.fallbackStatus
import net.jbglenn.telltype.core.model.insert.InsertReceipt
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.SessionTimer
import net.jbglenn.telltype.core.model.session.Stage
import net.jbglenn.telltype.core.model.session.Transition

/** Text to insert arrived; a blank text (only fillers were spoken) ends in "Didn't catch that". */
internal fun onCleaned(state: SessionState, text: CleanedText): Transition {
    val (working, stage) = state.inStage<Stage.Cleaning>() ?: return state.unchanged()
    return if (text.text.isBlank()) {
        failAfterAudio(working, NoSpeech, stage.transcript)
    } else {
        working.copy(stage = Stage.Inserting(stage.transcript, text))
            .with(SessionEffect.Insert(working.session.id, working.session.field, text.text))
    }
}

/** Dictation falls back to rule-based cleanup; Command Mode has no fallback and ends in Problem. */
internal fun onCleanupFailed(state: SessionState, error: CleanupError): Transition {
    val (working, stage) = state.inStage<Stage.Cleaning>() ?: return state.unchanged()
    return when (working.session.mode) {
        BubbleMode.DICTATE ->
            working.with(SessionEffect.CleanWithRules(working.session, stage.transcript, error.fallbackStatus))

        BubbleMode.COMMAND -> failAfterAudio(working, error, stage.transcript)
    }
}

/** The text is in the field: Done. In Done, the reply to Raw or Paste replaces the receipt. */
internal fun onInserted(state: SessionState, receipt: InsertReceipt): Transition {
    val inserting = state.inStage<Stage.Inserting>()
    return when {
        inserting != null -> {
            val (working, stage) = inserting
            val session = working.session
            val raw = stage.transcript.text.takeIf {
                session.mode == BubbleMode.DICTATE && !stage.text.snippetOnly && receipt.via.typed && it != receipt.text
            }
            val done = SessionState.Done(session, receipt, raw, working.focus)
            val watch = SessionEffect.WatchFieldEdits(receipt).takeIf { session.mode == BubbleMode.DICTATE }
            done.with(
                listOf(SessionEffect.DeleteRecording(working.recording), SessionEffect.Haptic(FeedbackCue.DONE)) +
                    saveHistory(session, stage.transcript, stage.text, receipt.via, null) +
                    SessionEffect.ScheduleTimer(chipTimer(done)) +
                    listOfNotNull(watch),
            )
        }

        state is SessionState.Done -> replaced(state, receipt)

        else -> state.unchanged()
    }
}

/**
 * Done after Raw or Paste: the new receipt without Raw. When the same field was re-typed (Raw), Undo still
 * restores what the field held before the dictation. The chip timer restarts.
 */
private fun replaced(done: SessionState.Done, receipt: InsertReceipt): Transition {
    val sameField = done.receipt.via.typed && receipt.target == done.receipt.target
    val next = done.copy(
        receipt = if (sameField) receipt.copy(replaced = done.receipt.replaced) else receipt,
        rawAlternative = null,
    )
    val old = chipTimer(done)
    val new = chipTimer(next)
    val cancel = SessionEffect.CancelTimer(old).takeIf { old != new }
    val limit = SessionEffect.CancelTimer(SessionTimer.HANDOFF_LIMIT).takeIf { done.handedOff && !next.handedOff }
    return next.with(listOfNotNull(cancel, limit) + SessionEffect.ScheduleTimer(new))
}

/** No ladder step worked: hand the text off (clipboard unless "Never use the clipboard"). */
internal fun onInsertionFailed(state: SessionState): Transition {
    val (working, stage) = state.inStage<Stage.Inserting>() ?: return state.unchanged()
    return working.with(SessionEffect.HandOff(working.session, stage.text.text))
}

/** The text was handed off: Done with Paste (or Copy). */
internal fun onHandedOff(state: SessionState, receipt: InsertReceipt): Transition {
    val (working, stage) = state.inStage<Stage.Inserting>() ?: return state.unchanged()
    val done = SessionState.Done(working.session, receipt, null, working.focus)
    return done.with(
        listOf(SessionEffect.DeleteRecording(working.recording), SessionEffect.Haptic(FeedbackCue.DONE)) +
            saveHistory(working.session, stage.transcript, stage.text, receipt.via, null) +
            doneTimers(done),
    )
}

/** The queue could not take the recording: the problem shows the storage error; the self-dismiss timer follows. */
internal fun onQueueFailed(state: SessionState, error: StorageError): Transition {
    if (state !is SessionState.Problem) return state.unchanged()
    val next = state.copy(error = error)
    val timer = when {
        dismissesItself(state) && !dismissesItself(next) -> SessionEffect.CancelTimer(SessionTimer.PROBLEM_DISMISS)
        !dismissesItself(state) && dismissesItself(next) -> SessionEffect.ScheduleTimer(SessionTimer.PROBLEM_DISMISS)
        else -> null
    }
    return next.with(listOfNotNull(timer))
}
