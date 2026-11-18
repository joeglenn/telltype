package net.jbglenn.telltype.network.cleanup

import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.domain.cleanup.ChatPrompt
import net.jbglenn.telltype.domain.cleanup.CleanupPrompt
import net.jbglenn.telltype.domain.cleanup.GuardInputs
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.ModelReply
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.domain.cleanup.applyGuards
import net.jbglenn.telltype.domain.cleanup.buildCleanupMessages
import net.jbglenn.telltype.domain.cleanup.buildCommandMessages
import net.jbglenn.telltype.domain.cleanup.extractReply

/**
 * The domain's prompt and guard functions the cleaner calls (contracts page 5, owned by A2b). An interface so the
 * cleaner can be tested with stubs; [DomainCleanupLogic] forwards to the real functions.
 */
internal interface CleanupLogic {
    /** `buildCleanupMessages`. */
    fun buildCleanup(request: CleanupRequest, prompts: PromptSet): CleanupPrompt

    /** `buildCommandMessages`. */
    fun buildCommand(request: CommandRequest, prompts: PromptSet): ChatPrompt

    /** `extractReply`. */
    fun extract(reply: ModelReply, sentInput: String, rules: GuardRules.ReplyExtraction): String

    /** `applyGuards`. */
    fun guard(reply: ModelReply, inputs: GuardInputs, rules: GuardRules): GuardVerdict
}

/** The real domain functions. */
internal object DomainCleanupLogic : CleanupLogic {
    override fun buildCleanup(request: CleanupRequest, prompts: PromptSet): CleanupPrompt =
        buildCleanupMessages(request, prompts)

    override fun buildCommand(request: CommandRequest, prompts: PromptSet): ChatPrompt =
        buildCommandMessages(request, prompts)

    override fun extract(reply: ModelReply, sentInput: String, rules: GuardRules.ReplyExtraction): String =
        extractReply(reply, sentInput, rules)

    override fun guard(reply: ModelReply, inputs: GuardInputs, rules: GuardRules): GuardVerdict =
        applyGuards(reply, inputs, rules)
}
