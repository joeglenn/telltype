package net.jbglenn.telltype.domain.settings

import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D58 and brief M1: the recommended models, the defaults after Setup and the alias migration. */
class ModelChoicesTest {
    private val telltype = listOf(
        OfferedModel("gemma-4-e4b-it-qat-q4_0"),
        OfferedModel("gemma-4-12b-it-qat-q4_0"),
        OfferedModel("qwen_qwen3.5-2b-q4_k_m"),
        OfferedModel("qwen_qwen3.5-4b-q4_k_m"),
        OfferedModel("qwen_qwen3.5-9b-q4_k_m"),
    )

    @Test
    fun theRecommendedCommandModelsMatchByIdOrFileNameInAnyCase() {
        val recommended = listOf(
            OfferedModel("Qwen_Qwen3.5-4B-Q4_K_M.gguf"),
            OfferedModel("qwen3.5:9b"),
            OfferedModel("QWEN3.5-9B-INSTRUCT"),
            OfferedModel("gemma-4-12b-it-qat-q4_0.gguf"),
            OfferedModel("gemma4:12b-qat"),
            OfferedModel("assistant-big", file = "Qwen3.5-4B-Q8_0.gguf"),
        )
        recommended.forEach { assertTrue(ModelChoices.isRecommended(it), it.toString()) }
        val others = listOf(
            OfferedModel("Qwen_Qwen3.5-2B-Q4_K_M.gguf"),
            OfferedModel("qwen3.5-14b"),
            OfferedModel("qwen3.6-35b-a3b"),
            OfferedModel("gemma-4-12b-it"),
            OfferedModel("gemma-4-e4b-it-qat"),
            OfferedModel("cleanup"),
        )
        others.forEach { assertFalse(ModelChoices.isRecommended(it), it.toString()) }
    }

    @Test
    fun theRecommendedCleanupModelIsGemma4E4b() {
        val recommended = listOf(
            OfferedModel("gemma-4-e4b-it-qat-q4_0"),
            OfferedModel("GEMMA-4-E4B_q4_0-it"),
            OfferedModel("gemma4:e4b"),
            OfferedModel("x", file = "/models/gemma-4-E4B_q4_0-it.gguf"),
        )
        recommended.forEach { assertTrue(ModelChoices.isRecommendedCleanup(it), it.toString()) }
        val others = listOf(OfferedModel("gemma-4-12b-it-qat"), OfferedModel("gemma-3-e4b"), OfferedModel("cleanup"))
        others.forEach { assertFalse(ModelChoices.isRecommendedCleanup(it), it.toString()) }
    }

    @Test
    fun cleanupDefaultsToTheServersCleanupModelThenTheRecommendedThenTheFirst() {
        assertEquals("qwen_qwen3.5-2b-q4_k_m", ModelChoices.defaultCleanup(telltype, "qwen_qwen3.5-2b-q4_k_m"))
        assertEquals("gemma-4-e4b-it-qat-q4_0", ModelChoices.defaultCleanup(telltype, "not-listed"))
        assertEquals("gemma-4-e4b-it-qat-q4_0", ModelChoices.defaultCleanup(telltype, null))
        val stock = listOf(OfferedModel("llama-3.2-1b"), OfferedModel("qwen3.5-9b"))
        assertEquals("llama-3.2-1b", ModelChoices.defaultCleanup(stock, null))
        assertNull(ModelChoices.defaultCleanup(emptyList(), "x"))
    }

    @Test
    fun commandDefaultsToQwen4bThenTheFirstRecommendedThenTheFirst() {
        assertEquals("qwen_qwen3.5-4b-q4_k_m", ModelChoices.defaultCommand(telltype))
        val stock = listOf(OfferedModel("llama-3.2-1b"), OfferedModel("qwen3.5-9b"), OfferedModel("gemma-4-12b-qat"))
        assertEquals("qwen3.5-9b", ModelChoices.defaultCommand(stock))
        assertEquals("llama-3.2-1b", ModelChoices.defaultCommand(stock.take(1)))
        assertNull(ModelChoices.defaultCommand(emptyList()))
    }

    @Test
    fun speechDefaultsToASpeechNamedModelOrNothing() {
        val mixed = listOf(OfferedModel("qwen3.5-4b"), OfferedModel("Systran/faster-whisper-small.en"))
        assertEquals("Systran/faster-whisper-small.en", ModelChoices.defaultSpeech(mixed))
        assertNull(ModelChoices.defaultSpeech(listOf(OfferedModel("qwen3.5-4b"))))
        assertNull(ModelChoices.defaultSpeech(null))
    }

    @Test
    fun aPathIsShownByItsFileNameWithoutGguf() {
        assertEquals("Qwen_Qwen3.5-2B-Q4_K_M", ModelChoices.displayName("/models/Qwen_Qwen3.5-2B-Q4_K_M.gguf"))
        assertEquals("gemma-4-e4b", ModelChoices.displayName("gemma-4-e4b.GGUF"))
        assertEquals("Systran/faster-whisper-small.en", ModelChoices.displayName("Systran/faster-whisper-small.en"))
    }

    @Test
    fun aChoiceStaysOnlyWhileTheServerListsIt() {
        val list = listOf(OfferedModel("a"), OfferedModel("b"))
        assertEquals("b", ModelChoices.keepIfListed("b", list, "a"))
        assertEquals("a", ModelChoices.keepIfListed("gone", list, "a"))
        assertEquals("unchecked", ModelChoices.keepIfListed("unchecked", null, "a"))
    }

    @Test
    fun savedAliasesAreReplacedByTheRealNames() {
        val saved = ServerModels(speech = "parakeet", cleanup = "cleanup", command = "assistant")
        assertEquals(
            ServerModels(speech = "parakeet", cleanup = "gemma-4-e4b-it-qat-q4_0", command = "qwen_qwen3.5-4b-q4_k_m"),
            ModelChoices.replaceAliases(saved, telltype, "gemma-4-e4b-it-qat-q4_0"),
        )
    }

    @Test
    fun otherChoicesUnknownListsAndRealModelsNamedLikeAnAliasAreKept() {
        val chosen = ServerModels(cleanup = "qwen_qwen3.5-2b-q4_k_m", command = "qwen_qwen3.5-9b-q4_k_m")
        assertEquals(chosen, ModelChoices.replaceAliases(chosen, telltype, null))
        val aliases = ServerModels(cleanup = "cleanup", command = "assistant")
        assertSame(aliases, ModelChoices.replaceAliases(aliases, null, null))
        assertSame(aliases, ModelChoices.replaceAliases(aliases, emptyList(), null))
        val stock = listOf(OfferedModel("cleanup"), OfferedModel("qwen3.5-9b"))
        assertEquals(
            ServerModels(cleanup = "cleanup", command = "qwen3.5-9b"),
            ModelChoices.replaceAliases(aliases, stock, null),
        )
    }
}
