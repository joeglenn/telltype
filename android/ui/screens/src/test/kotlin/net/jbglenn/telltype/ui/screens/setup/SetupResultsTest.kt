package net.jbglenn.telltype.ui.screens.setup

import net.jbglenn.telltype.core.model.settings.ModelRole
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** B10: Setup's model defaults on the first Setup and on every later check (a new key, a re-check). */
class SetupResultsTest {
    private val cleanupAndCommands = setOf(ModelRole.CLEANUP, ModelRole.COMMANDS)
    private val commands = setOf(ModelRole.COMMANDS)

    /** What Telltype Server 1.0.1 lists on the server, in its order. */
    private val withRoles = listOf(
        OfferedModel("parakeet-unified-en-0.6b-int8", roles = setOf(ModelRole.SPEECH)),
        OfferedModel("gemma-4-e4b-it-qat-q4_0", roles = cleanupAndCommands),
        OfferedModel("gemma-4-12b-it-qat-q4_0", roles = commands),
        OfferedModel("qwen_qwen3.5-2b-q4_k_m", roles = commands),
        OfferedModel("qwen_qwen3.5-4b-q4_k_m", roles = commands),
    )

    /** The same server before roles (1.0.0): the speech model first and the cleanup file twice. */
    private val withoutRoles = listOf(
        OfferedModel("parakeet-unified-en-0.6b-int8"),
        OfferedModel("gemma-4-12b-it-qat-q4_0", "gemma-4-12b-it-qat-q4_0.gguf"),
        OfferedModel("gemma-4-e4b-it-qat-q4_0"),
        OfferedModel("gemma-4-e4b_q4_0-it", "gemma-4-E4B_q4_0-it.gguf"),
        OfferedModel("qwen_qwen3.5-4b-q4_k_m", "Qwen_Qwen3.5-4B-Q4_K_M.gguf"),
    )

    private fun check(models: List<OfferedModel>) = ServerCheck(
        endpoint = ServerEndpoint("192.0.2.10", 8170),
        methods = mapOf(ServerRole.SPEECH to ServerCheckMethod.MODELS, ServerRole.TEXT to ServerCheckMethod.MODELS),
        models = models,
        health = null,
    )

    private val expected = ServerModels(
        speech = "parakeet-unified-en-0.6b-int8",
        cleanup = "gemma-4-e4b-it-qat-q4_0",
        command = "qwen_qwen3.5-4b-q4_k_m",
    )

    @Test
    fun firstSetupAndEveryRecheckGiveGemmaE4bAndQwen4b() {
        for (models in listOf(withRoles, withoutRoles)) {
            val first = chooseModels(ServerModels.Unset, check(models), check(models))
            assertEquals(expected, first)
            assertEquals("a re-check keeps the defaults", expected, chooseModels(first, check(models), check(models)))
            val aliases = ServerModels(cleanup = "cleanup", command = "assistant")
            assertEquals("after a key change", expected, chooseModels(aliases, check(models), check(models)))
        }
    }

    @Test
    fun aSpeechModelChosenForTextIsReplacedButARealChoiceStays() {
        val wrong = ServerModels(cleanup = "parakeet-unified-en-0.6b-int8", command = "parakeet-unified-en-0.6b-int8")
        assertEquals(expected, chooseModels(wrong, check(withRoles), check(withRoles)))
        val own = ServerModels(cleanup = "gemma-4-e4b-it-qat-q4_0", command = "gemma-4-12b-it-qat-q4_0")
        assertEquals(
            own.copy(speech = expected.speech),
            chooseModels(own, check(withRoles), check(withRoles)),
        )
    }
}
