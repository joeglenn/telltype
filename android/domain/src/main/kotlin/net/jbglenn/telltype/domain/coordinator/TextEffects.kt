package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.fold
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.error.CleanupError
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.SelectionUnreadable
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Session
import net.jbglenn.telltype.core.model.session.SessionEffect
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.domain.cleanup.isShortUtterance
import net.jbglenn.telltype.domain.cleanup.ruleBasedCleanup
import net.jbglenn.telltype.domain.text.FieldMatchInput
import net.jbglenn.telltype.domain.text.SnippetMatch
import net.jbglenn.telltype.domain.text.matchField
import net.jbglenn.telltype.domain.text.matchSnippet

/**
 * `Clean` and `CleanWithRules` (docs/PLAN.md sections 4.6, 8.3 and 8.4). Dictation: a whole-utterance snippet is
 * inserted directly; with cleanup off or a short phrase the rules run (`OFF`, `SKIPPED_SHORT`); otherwise the model
 * runs within the deadline setting ("always wait" waits up to `modelCeiling`), a timeout being `CleanupTimedOut`.
 * A model failure replies `CleanupFailed`, and the reducer asks for `CleanWithRules`. Command Mode: the selection
 * read at record start and the spoken instruction go to the model; its reply replaces the selection as it is.
 */
internal class TextEffects(private val ctx: CoordinatorContext, private val persistence: PersistenceEffects) {
    /** Runs `Clean` in its session's job; replies `Cleaned` or `CleanupFailed`. */
    fun clean(effect: SessionEffect.Clean) {
        val (scope, record) = ctx.sessions.find(effect.session.id) ?: return ctx.dropped(effect)
        scope.launch {
            val started = ctx.clock.monotonic()
            val reply = when (effect.session.mode) {
                BubbleMode.DICTATE -> dictate(effect.session, effect.transcript, record)
                BubbleMode.COMMAND -> command(effect.session, effect.transcript, record)
            }
            record.addCleanup(ctx.clock.monotonic() - started)
            ctx.reply(reply)
        }
    }

    /** Runs `CleanWithRules` in its session's job; replies `Cleaned` with the effect's status. */
    fun cleanWithRules(effect: SessionEffect.CleanWithRules) {
        val (scope, record) = ctx.sessions.find(effect.session.id) ?: return ctx.dropped(effect)
        scope.launch {
            val started = ctx.clock.monotonic()
            val inputs = loadInputs(record)
            val match = matchSnippet(effect.transcript.text, inputs.snippets, ctx.environment.guardRules.snippetMarkers)
            val text = if (match is SnippetMatch.WholeUtterance) {
                snippet(match, inputs)
            } else {
                withRules(effect.transcript, match, inputs, effect.status)
            }
            record.addCleanup(ctx.clock.monotonic() - started)
            ctx.reply(SessionEvent.Cleaned(effect.session.id, text))
        }
    }

    private suspend fun dictate(session: Session, transcript: Transcript, record: SessionRecord): SessionEvent {
        val inputs = loadInputs(record)
        val match = matchSnippet(transcript.text, inputs.snippets, ctx.environment.guardRules.snippetMarkers)
        val cleanup = ctx.settings.cleanup
        val text = when {
            match is SnippetMatch.WholeUtterance -> snippet(match, inputs)

            !cleanup.enabled -> withRules(transcript, match, inputs, CleanupStatus.OFF)

            cleanup.skipShortPhrases && isShortUtterance(transcript.text) ->
                withRules(transcript, match, inputs, CleanupStatus.SKIPPED_SHORT)

            else -> null
        }
        return if (text != null) SessionEvent.Cleaned(session.id, text) else model(session, transcript, match, inputs)
    }

    private suspend fun model(
        session: Session,
        transcript: Transcript,
        match: SnippetMatch,
        inputs: TextInputs,
    ): SessionEvent {
        val style = ctx.sessions.recordFor(session.id)?.style ?: ctx.styleFor(session.field.app)
        val request = CleanupRequest(
            session = session.id,
            transcript = match.modelInput(transcript.text),
            tone = style.tone,
            category = style.category,
            dictionary = inputs.terms.map { it.term },
            contextBefore = inputs.context.textBefore,
            fieldHint = session.field.hint.takeUnless { session.field.incognito },
        )
        val deadline = ctx.settings.cleanup.modelDeadline.takeIf { it.isFinite() } ?: ctx.config.modelCeiling
        val timedOut: Outcome<CleanedText, CleanupError> = Outcome.Err(CleanupTimedOut)
        val outcome = ctx.calls.call("cleanup", deadline, timedOut, Outcome.Err(MalformedResponse)) {
            ctx.ports.cleaner.clean(request)
        }
        return when (outcome) {
            is Outcome.Ok -> {
                ctx.sessions.recordFor(session.id)?.addServerTimings(outcome.value.serverTimings)
                val text = process(outcome.value.text, match, inputs)
                SessionEvent.Cleaned(session.id, outcome.value.copy(text = text, snippetOnly = false))
            }

            is Outcome.Err -> SessionEvent.CleanupFailed(session.id, outcome.error)
        }
    }

    private suspend fun command(session: Session, transcript: Transcript, record: SessionRecord): SessionEvent {
        val selection = awaitContext(record).selectedText
        ctx.logger.info(CoordinatorTag) {
            "session ${session.id.value}: command mode, selection ${selection.length} chars, " +
                "instruction transcribed (${transcript.text.length} chars)"
        }
        val started = ctx.clock.monotonic()
        val outcome: Outcome<CleanedText, CleanupError> = if (selection.isEmpty()) {
            Outcome.Err(SelectionUnreadable)
        } else {
            val request = CommandRequest(session.id, selection, transcript.text)
            ctx.calls.call(
                "command",
                ctx.config.modelCeiling,
                Outcome.Err(CleanupTimedOut),
                Outcome.Err(MalformedResponse),
            ) {
                ctx.ports.cleaner.command(request)
            }
        }
        val spent = ctx.clock.monotonic() - started
        return outcome.fold(
            onOk = { text ->
                ctx.logger.info(CoordinatorTag) {
                    "session ${session.id.value}: command rewrite applied (${text.text.length} chars) after $spent, " +
                        "server timings ${text.serverTimings}"
                }
                record.addServerTimings(text.serverTimings)
                SessionEvent.Cleaned(session.id, text)
            },
            onErr = { error ->
                ctx.logger.info(CoordinatorTag) {
                    "session ${session.id.value}: command rewrite refused (${error.code}) after $spent"
                }
                SessionEvent.CleanupFailed(session.id, error)
            },
        )
    }

    private suspend fun withRules(
        transcript: Transcript,
        match: SnippetMatch,
        inputs: TextInputs,
        status: CleanupStatus,
    ): CleanedText {
        val rules = ctx.environment.guardRules
        val ruled = ctx.onDefault { ruleBasedCleanup(match.modelInput(transcript.text), rules) }
        ctx.logger.info(CoordinatorTag) { "cleanup by rules ($status), ${ruled.length} chars" }
        return CleanedText(process(ruled, match, inputs), status, snippetOnly = false, serverTimings = emptyList())
    }

    /** A whole-utterance snippet: its saved text, fitted to the field, without the model or replacements. */
    private suspend fun snippet(match: SnippetMatch.WholeUtterance, inputs: TextInputs): CleanedText {
        val text = ctx.onDefault { matchField(match.snippet.text, inputs.field) }
        ctx.logger.info(CoordinatorTag) { "snippet expanded: ${match.snippet.id.value}, ${text.length} chars" }
        persistence.recordUses(inputs, Processed(text, emptySet()), setOf(match.snippet.id))
        return CleanedText(text, CleanupStatus.SKIPPED_SHORT, snippetOnly = true, serverTimings = emptyList())
    }

    private suspend fun process(text: String, match: SnippetMatch, inputs: TextInputs): String {
        val processed = ctx.onDefault { postProcess(text, match, inputs) }
        val snippets = (match as? SnippetMatch.Embedded)?.markers.orEmpty().map { it.snippet.id }.toSet()
        persistence.recordUses(inputs, processed, snippets)
        return processed.text
    }

    private suspend fun loadInputs(record: SessionRecord): TextInputs = coroutineScope {
        val snippets = async { ctx.firstOf("snippets", ctx.stores.snippets.snippets, emptyList()) }
        val replacements = async { ctx.firstOf("replacements", ctx.stores.dictionary.replacements, emptyList()) }
        val terms = async { ctx.firstOf("dictionary terms", ctx.stores.dictionary.terms, emptyList()) }
        val context = awaitContext(record)
        val field = FieldMatchInput(
            kind = record.field.kind,
            textBefore = context.textBefore,
            textAfter = context.textAfter,
            category = record.style.category,
            tone = record.style.tone,
            protectedWords = terms.await().map { it.term },
        )
        TextInputs(snippets.await(), replacements.await(), terms.await(), context, field, record.field.incognito)
    }

    private suspend fun awaitContext(record: SessionRecord): FieldContext {
        val pending = record.context
        return if (pending == null) {
            FieldContext.Empty
        } else {
            ctx.calls.call("context wait", ctx.config.contextRead, FieldContext.Empty) { pending.await() }
        }
    }
}
