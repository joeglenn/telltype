package net.jbglenn.telltype.domain.cleanup

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict

/**
 * Parses the bundled `shared/prompts/` files. [files] maps each file name in that directory (`VERSION`,
 * `cleanup_system.txt`, `cleanup_user.tmpl`, `command_system.txt`, `command_user.tmpl`, `tones.json`,
 * `categories.json`, `request.json`) to its UTF-8 content. Fails when a file is missing, a template's placeholder
 * set differs from the documented one, a tone or category lacks an entry, or `stream` is true
 * (`shared/prompts/README.md`). Also fails on a JSON key that the format does not have, on a `chat_template_kwargs`
 * entry other than `enable_thinking`, and on a `max_tokens` rule with `chars_per_token <= 0` or `max < min`.
 * Line endings are read as Python's text mode does (`\r\n` and `\r` become `\n`).
 */
public fun parsePromptSet(files: Map<String, String>): Outcome<PromptSet, AssetError> = decodePromptSet(files)

/**
 * Parses the bundled `shared/guards.json` ([json] is its content). Fails on any structural difference: a missing
 * or unknown key, a value of the wrong type, an unknown or repeated guard in `order`, an empty entity, quote,
 * suffix or phrase, an unknown `unicode_form`, or a `snippet_markers.regex` that does not say exactly what `open`,
 * `prefix`, `close` and `max_digits` say.
 */
public fun parseGuardRules(json: String): Outcome<GuardRules, AssetError> = decodeGuardRules(json)

/**
 * Builds the cleanup chat request exactly as `shared/prompts/README.md` ("Building the cleanup request") and the
 * snapshot cases in `eval/tests/test_prompt_builder.py` define: trims and de-duplicates the dictionary, cuts the
 * context to [PromptSet.contextBeforeMaxChars] code points, escapes every value, substitutes in one pass, and
 * computes `max_tokens`. Pure and total: a [PromptSet] from [parsePromptSet] has every tone and category; for a
 * hand-made one without the entry the value is `""`.
 */
public fun buildCleanupMessages(request: CleanupRequest, prompts: PromptSet): CleanupPrompt =
    cleanupPrompt(request, prompts)

/** Builds the Command Mode chat request (`command_user.tmpl`; selection escaped but not trimmed). Pure and total. */
public fun buildCommandMessages(request: CommandRequest, prompts: PromptSet): ChatPrompt =
    commandPrompt(request, prompts)

/**
 * Reply extraction (`shared/GUARDS.md` section 2): null content becomes `""`, line endings normalised, a leading
 * think block dropped, entities unescaped in one pass, trimmed, one pair of wrapping quotes removed unless
 * [sentInput] (the trimmed transcript, or the selection in Command Mode) starts with the same quote.
 */
public fun extractReply(reply: ModelReply, sentInput: String, rules: GuardRules.ReplyExtraction): String =
    extractReplyText(reply.content, sentInput, rules)

/**
 * Extracts the reply and runs the guards of [GuardRules.order] over it (`shared/GUARDS.md` sections 2 to 4). The
 * first failing guard decides; [GuardVerdict.Accepted] carries the extracted text. Must agree with
 * `eval/guards.py` on every conformance case in `eval/tests/test_guards.py`.
 */
public fun applyGuards(reply: ModelReply, inputs: GuardInputs, rules: GuardRules): GuardVerdict {
    val extracted = extractReplyText(reply.content, inputs.transcript, rules.replyExtraction)
    return checkGuards(GuardSubject(extracted, inputs, reply.finishReason, rules), rules)
}

/**
 * The rule-based fallback cleanup of a raw transcript (`shared/GUARDS.md` section 5): drops fillers and stray
 * punctuation, capitalises "I" and sentence starts, adds final punctuation. Snippet markers pass through. Must
 * agree with `eval/tests/test_fallback.py`. Field matching and dictionary replacements are separate steps.
 *
 * Takes the whole [GuardRules] (A2a declared `GuardRules.Fallback`): steps 4 and 7 compare words after the
 * normalisation of section 3, whose settings live in [GuardRules.normalisation].
 */
public fun ruleBasedCleanup(transcript: String, rules: GuardRules): String = fallbackCleanup(transcript, rules)

/**
 * Whether [transcript] is a few words with no correction cue ("no wait", "actually", "I mean", "scratch that"),
 * so the model is skipped when `Settings.cleanup.skipShortPhrases` is on (docs/PLAN.md section 8.3): one to three
 * words, none of the cues `no`, `wait`, `actually`, `sorry`, `I mean`, `scratch that`, `make that` (case
 * insensitive, whole words). The limit and the cues are named constants in `:domain` (`ShortUtterance`), because
 * `shared/` has no file for them.
 */
public fun isShortUtterance(transcript: String): Boolean = isShort(transcript)
