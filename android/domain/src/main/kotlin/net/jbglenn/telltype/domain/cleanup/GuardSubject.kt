package net.jbglenn.telltype.domain.cleanup

/**
 * Everything the guards look at (`shared/GUARDS.md` section 1), with the token lists computed once.
 *
 * @property reply the reply after extraction.
 * @property inputs what was sent.
 * @property finishReason `choices[0].finish_reason`, null when absent.
 */
internal class GuardSubject(val reply: String, val inputs: GuardInputs, val finishReason: String?, rules: GuardRules) {
    /** The transcript as sent. */
    val transcript: String get() = inputs.transcript

    /** The text before the cursor as sent. */
    val contextBefore: String get() = inputs.contextBefore

    /** The dictionary terms as sent. */
    val dictionary: List<String> get() = inputs.dictionary

    /** `tokens(reply)`. */
    val replyTokens: List<Token> by lazy { tokensOf(reply, rules) }

    /** `tokens(transcript)`. */
    val transcriptTokens: List<Token> by lazy { tokensOf(transcript, rules) }

    override fun toString(): String = "GuardSubject(reply=‹${reply.length} chars›, inputs=$inputs)"
}
