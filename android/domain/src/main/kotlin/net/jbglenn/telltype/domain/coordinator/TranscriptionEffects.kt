package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.fold
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.SpeechEngineFailed
import net.jbglenn.telltype.core.model.error.SttError
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript

/**
 * `Transcribe`: the dictionary terms as hints, then the engine. A timeout counts as `ServerUnreachable` (the audio
 * is queued) and a thrown exception as `SpeechEngineFailed`. Replies `Transcribed` or `TranscriptionFailed`.
 */
internal class TranscriptionEffects(private val ctx: CoordinatorContext) {
    /** Runs [effect] in its session's job. */
    fun transcribe(effect: SessionEffect.Transcribe) {
        val (scope, record) = ctx.sessions.find(effect.session.id) ?: return ctx.dropped(effect)
        scope.launch {
            val terms = ctx.firstOf("dictionary terms", ctx.stores.dictionary.terms, emptyList())
            val hints = SttHints(terms.map { it.term })
            val started = ctx.clock.monotonic()
            val unreachable: Outcome<Transcript, SttError> = Outcome.Err(ServerUnreachable)
            val outcome = ctx.calls.call(
                name = "transcription",
                timeout = ctx.config.transcription,
                onTimeout = unreachable,
                onError = Outcome.Err(SpeechEngineFailed),
            ) { ctx.ports.speech.transcribe(effect.clip, hints) }
            record.timings = record.timings.copy(transcription = ctx.clock.monotonic() - started)
            val reply = outcome.fold(
                onOk = { transcript ->
                    record.addServerTimings(transcript.serverTimings)
                    SessionEvent.Transcribed(effect.session.id, transcript)
                },
                onErr = { SessionEvent.TranscriptionFailed(effect.session.id, it) },
            )
            ctx.reply(reply)
        }
    }
}
