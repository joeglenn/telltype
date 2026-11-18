package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.flow.MutableSharedFlow
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * Runs each effect the reducer returned, in list order. Instant effects (haptics, chips, timers, cancellation) run
 * synchronously in the loop; the others are launched: session work in the session's job, recorder work through
 * the capture lane, writes through the persistence lane, the rest in the coordinator's scope.
 */
internal class EffectRunner(private val ctx: CoordinatorContext, chips: MutableSharedFlow<Chip>) {
    private val persistence = PersistenceEffects(ctx)
    private val capture = CaptureEffects(ctx)
    private val transcription = TranscriptionEffects(ctx)
    private val text = TextEffects(ctx, persistence)
    private val insertion = InsertionEffects(ctx)
    private val learning = LearningWatch(ctx)
    private val timers = TimerBank(ctx)
    private val system = SystemEffects(ctx, chips)

    /** The event [inbound] stands for, or null when it is a stale start reply or a stale timer expiry. */
    fun accept(inbound: Inbound): SessionEvent? = when (inbound) {
        is Inbound.External -> inbound.event
        is Inbound.StartReply -> inbound.event.takeIf { ctx.sessions.isPendingStart(inbound.start) }
        is Inbound.TimerExpired -> SessionEvent.TimerFired(inbound.timer).takeIf { timers.accept(inbound) }
    }

    /** Runs [effect]. */
    // One branch per effect type keeps the dispatch exhaustive; each branch is a single call.
    @Suppress("CyclomaticComplexMethod")
    fun run(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartRecording -> {
                learning.stop()
                capture.start(effect)
            }

            SessionEffect.AbortRecordingStart -> capture.abort()

            is SessionEffect.StopRecording -> capture.stop(effect)

            is SessionEffect.CancelRecording -> capture.cancelRecording(effect.recording)

            is SessionEffect.DeleteRecording -> capture.deleteRecording(effect.recording)

            is SessionEffect.Transcribe -> transcription.transcribe(effect)

            is SessionEffect.Clean -> text.clean(effect)

            is SessionEffect.CleanWithRules -> text.cleanWithRules(effect)

            is SessionEffect.Insert -> insertion.insert(effect)

            is SessionEffect.ReplaceInserted -> insertion.replace(effect)

            is SessionEffect.HandOff -> insertion.handOff(effect)

            is SessionEffect.CopyToClipboard -> insertion.copy(effect.text)

            is SessionEffect.CancelWork -> ctx.sessions.cancel(effect.session)

            is SessionEffect.SaveHistory -> persistence.saveHistory(effect)

            is SessionEffect.EnqueuePendingJob -> persistence.enqueue(effect)

            is SessionEffect.Haptic -> system.haptic(effect.cue)

            is SessionEffect.ShowChip -> system.chip(effect.chip)

            is SessionEffect.OpenFix -> system.openFix(effect.fix)

            is SessionEffect.ScheduleTimer -> timers.schedule(effect.timer)

            is SessionEffect.CancelTimer -> timers.cancel(effect.timer)

            is SessionEffect.WatchFieldEdits -> learning.watch(effect.receipt)

            is SessionEffect.SaveCorrection -> persistence.saveCorrection(effect.correction)

            SessionEffect.ProbeServerHealth -> system.probe()
        }
    }

    /** After a transition's effects: retire the sessions the state left, release the microphone when idle. */
    fun settle(state: SessionState) {
        ctx.sessions.settle(state)
        capture.settle()
    }

    /** On close: release the recorder and the microphone service. */
    suspend fun teardown(state: SessionState) {
        capture.teardown(state)
    }
}
