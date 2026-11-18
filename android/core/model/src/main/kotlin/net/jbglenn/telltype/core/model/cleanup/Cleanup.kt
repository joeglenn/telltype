package net.jbglenn.telltype.core.model.cleanup

import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.lengthOnly
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/**
 * Everything the cleanup prompt is built from (docs/PLAN.md section 8.3, `shared/prompts/README.md`). Values are
 * raw: the prompt builder trims, cuts and escapes them. For an incognito field the coordinator sends
 * [contextBefore] `""`, [fieldHint] null and [category] [AppCategory.OTHER].
 *
 * @property session the session, sent as `X-Request-Id`.
 * @property transcript the raw transcript, with snippet markers (`⟦S1⟧`) already in place of embedded triggers.
 * @property tone the tone for this app.
 * @property category the app's category.
 * @property dictionary the dictionary's correct spellings; may be empty.
 * @property contextBefore the text before the cursor as read at record start; `""` when none.
 * @property fieldHint the field's hint, null when it has none.
 */
public data class CleanupRequest(
    public val session: SessionId,
    public val transcript: String,
    public val tone: Tone,
    public val category: AppCategory,
    public val dictionary: List<String>,
    public val contextBefore: String,
    public val fieldHint: String?,
) {
    override fun toString(): String = "CleanupRequest(session=$session, transcript=${transcript.lengthOnly()}, " +
        "tone=$tone, category=$category, dictionary=${dictionary.size} terms, " +
        "contextBefore=${contextBefore.lengthOnly()}, fieldHint=${fieldHint?.lengthOnly()})"
}

/**
 * A Command Mode request (docs/PLAN.md section 4.7): rewrite [selection] as [instruction] says.
 *
 * @property session the session, sent as `X-Request-Id`.
 * @property selection the selected text, exactly as read from the field (not trimmed: it is replaced verbatim).
 * @property instruction the spoken instruction (the transcript).
 */
public data class CommandRequest(
    public val session: SessionId,
    public val selection: String,
    public val instruction: String,
) {
    override fun toString(): String = "CommandRequest(session=$session, selection=${selection.lengthOnly()}, " +
        "instruction=${instruction.lengthOnly()})"
}

/**
 * How the final text of a dictation was produced; the `history.cleanup_status` column (docs/PLAN.md Appendix E).
 *
 * @property id stable id stored in the database. Never change one.
 */
public enum class CleanupStatus(public val id: String) {
    /** The model's reply passed every guard and was used. */
    APPLIED("applied"),

    /** The model replied but a guard rejected it; rule-based cleanup was used ("cleanup skipped"). */
    SKIPPED_GUARD("skipped_guard"),

    /** The model missed its deadline; rule-based cleanup was used. */
    SKIPPED_TIMEOUT("skipped_timeout"),

    /** The utterance was a few words with no correction cue, so the model was skipped (section 8.3). */
    SKIPPED_SHORT("skipped_short"),

    /**
     * The cleanup request failed (server unreachable, backend error, key rejected); rule-based cleanup was used.
     * Not in Appendix E's list: added by A2a because none of the other values describes this case.
     */
    SKIPPED_ERROR("skipped_error"),

    /** The model was not used by design: cleanup is switched off, or a whole-utterance snippet was inserted. */
    OFF("off"),
}

/**
 * Text ready for field matching and insertion, with how it was produced.
 *
 * @property text the text.
 * @property status how it was produced. A `TextCleaner` returns only [CleanupStatus.APPLIED]; the other values
 *   are set by the domain when it used rule-based cleanup or a snippet instead.
 * @property snippetOnly the whole utterance matched a snippet trigger and [text] is the snippet (history mode
 *   `snippet`).
 * @property serverTimings the `Server-Timing` entries of the model call; empty when no model was called.
 */
public data class CleanedText(
    public val text: String,
    public val status: CleanupStatus,
    public val snippetOnly: Boolean,
    public val serverTimings: List<ServerTiming>,
) {
    override fun toString(): String = "CleanedText(text=${text.lengthOnly()}, status=$status, " +
        "snippetOnly=$snippetOnly, serverTimings=$serverTimings)"
}

/**
 * The output guards of `shared/guards.json`, in the order they run (`shared/GUARDS.md` section 4).
 *
 * @property id the guard's name in `guards.json` (`order` list and section keys). Never change one.
 */
public enum class Guard(public val id: String) {
    /** Guard 1: empty or whitespace reply, or only punctuation. */
    EMPTY("empty"),

    /** Guard 2: refusals and assistant talk the speaker did not say. */
    ASSISTANT_PHRASING("assistant_phrasing"),

    /** Guard 3: reply cut off, or far longer or shorter than the transcript. */
    LENGTH("length"),

    /** Guard 4: too many words that were never spoken. */
    UNSPOKEN_WORDS("unspoken_words"),

    /** Guard 5: prompt tags or prompt/context text repeated. */
    ECHO("echo"),

    /** Guard 6: snippet markers lost, duplicated or invented. */
    SNIPPET_MARKERS("snippet_markers"),
}

/** Which bound guard 3 ([Guard.LENGTH]) found broken. */
public enum class LengthViolation {
    /** The server stopped at the token cap (`finish_reason` in `length.fail_on_finish_reason`). */
    CUT_OFF,

    /** More reply tokens than the upper bound. */
    TOO_LONG,

    /** Fewer reply tokens than the lower bound. */
    TOO_SHORT,
}

/** What guard 5 ([Guard.ECHO]) found repeated in the reply. */
public enum class EchoSource {
    /** A prompt tag such as `<transcript` or `</context` that the transcript did not contain. */
    PROMPT_TAG,

    /** Words copied from the system prompt. */
    SYSTEM_PROMPT,

    /** Words copied from the tone line. */
    TONE_LINE,

    /** Words copied from the dictionary list. */
    DICTIONARY,

    /** Words copied from the text before the cursor. */
    CONTEXT_BEFORE,
}

/**
 * The result of running the guards over a cleanup reply (`shared/GUARDS.md`). Exactly one [Rejected] case per
 * guard in `shared/guards.json`; the first failing guard decides.
 */
public sealed interface GuardVerdict {
    /**
     * Every guard passed.
     *
     * @property text the reply after extraction (think block dropped, entities unescaped, trimmed, wrapping quotes
     *   removed): what is inserted.
     */
    public data class Accepted(public val text: String) : GuardVerdict {
        override fun toString(): String = "Accepted(text=${text.lengthOnly()})"
    }

    /** A guard failed: rule-based cleanup of the raw transcript is used and History notes "cleanup skipped". */
    public sealed interface Rejected : GuardVerdict {
        /** The guard that failed. */
        public val guard: Guard
    }

    /** Guard 1 failed. */
    public data object EmptyReply : Rejected {
        override val guard: Guard get() = Guard.EMPTY
    }

    /**
     * Guard 2 failed.
     *
     * @property phrase the `guards.json` phrase that matched (from the shipped list, not user text).
     */
    public data class AssistantPhrasing(public val phrase: String) : Rejected {
        override val guard: Guard get() = Guard.ASSISTANT_PHRASING
    }

    /**
     * Guard 3 failed.
     *
     * @property violation which bound broke.
     * @property replyTokens number of reply tokens.
     * @property bound the bound that was broken, in tokens; null for [LengthViolation.CUT_OFF].
     */
    public data class LengthOutOfBounds(
        public val violation: LengthViolation,
        public val replyTokens: Int,
        public val bound: Double?,
    ) : Rejected {
        override val guard: Guard get() = Guard.LENGTH
    }

    /**
     * Guard 4 failed.
     *
     * @property words the reply tokens that were never spoken, in reply order, one per occurrence. Dictated
     *   content: never logged (`toString` shows only the count).
     * @property allowance how many unspoken tokens the reply was allowed.
     */
    public data class UnspokenWords(public val words: List<String>, public val allowance: Int) : Rejected {
        override val guard: Guard get() = Guard.UNSPOKEN_WORDS

        override fun toString(): String = "UnspokenWords(words=${words.size}, allowance=$allowance)"
    }

    /**
     * Guard 5 failed.
     *
     * @property source what was repeated.
     */
    public data class Echo(public val source: EchoSource) : Rejected {
        override val guard: Guard get() = Guard.ECHO
    }

    /**
     * Guard 6 failed.
     *
     * @property expected how often each marker (`⟦S1⟧`) occurred in the transcript.
     * @property actual how often each marker occurred in the reply.
     */
    public data class SnippetMarkersChanged(
        public val expected: Map<String, Int>,
        public val actual: Map<String, Int>,
    ) : Rejected {
        override val guard: Guard get() = Guard.SNIPPET_MARKERS
    }
}
