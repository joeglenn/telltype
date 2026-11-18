package net.jbglenn.telltype.network.http

import net.jbglenn.telltype.core.model.settings.ModelRole
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.domain.settings.ModelChoices
import net.jbglenn.telltype.domain.settings.ModelRoles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B10: the model lists built from real `/v1/models` replies (Telltype Server 1.0.0 and 1.0.1, captured from a running
 * server with local paths rewritten and the list shortened) and a stock mix of llama-server, Ollama and speaches.
 */
class ModelListRolesTest {
    private fun load(name: String): List<OfferedModel> {
        val body = checkNotNull(javaClass.getResource("/models/$name")).readText()
        return checkNotNull(parseModelList(body))
    }

    private fun ids(models: List<OfferedModel>, role: ModelRole) = ModelRoles.forRole(models, role).map { it.id }

    @Test
    fun serverRolesDecideEachListAndTheDuplicateFileIsGone() {
        val models = load("telltype-server-1.0.1.json")
        assertEquals(setOf(ModelRole.CLEANUP, ModelRole.COMMANDS), models[1].roles)
        assertEquals(listOf("parakeet-unified-en-0.6b-int8"), ids(models, ModelRole.SPEECH))
        assertEquals(listOf("gemma-4-e4b-it-qat-q4_0"), ids(models, ModelRole.CLEANUP))
        val commands = ids(models, ModelRole.COMMANDS)
        assertFalse(commands.any { "parakeet" in it || it == "gemma-4-e4b_q4_0-it" })
        assertEquals(commands.distinct(), commands)
        assertEquals(setOf("gemma-4-e4b-it-qat-q4_0"), ModelRoles.recommendedIds(models, ModelRole.CLEANUP))
        assertEquals(
            setOf("gemma-4-12b-it-qat-q4_0", "qwen_qwen3.5-4b-q4_k_m", "qwen_qwen3.5-9b-q4_k_m"),
            ModelRoles.recommendedIds(ModelRoles.forRole(models, ModelRole.COMMANDS), ModelRole.COMMANDS),
        )
        assertEquals("gemma-4-e4b-it-qat-q4_0", ModelChoices.defaultCleanup(models, serverCleanup = null))
        assertEquals("qwen_qwen3.5-4b-q4_k_m", ModelChoices.defaultCommand(models))
        assertEquals("parakeet-unified-en-0.6b-int8", ModelChoices.defaultSpeech(models))
    }

    @Test
    fun withoutRolesTheSpeechModelStaysOutOfTheTextListsAndRecommendedAppearsOnce() {
        val models = load("telltype-server-1.0.0.json")
        assertTrue(models.all { it.roles == null })
        assertEquals("gemma-4-E4B_q4_0-it.gguf", models.first { it.id == "gemma-4-e4b_q4_0-it" }.file)
        assertEquals(listOf("parakeet-unified-en-0.6b-int8"), ids(models, ModelRole.SPEECH))
        val cleanup = ModelRoles.forRole(models, ModelRole.CLEANUP)
        assertFalse(cleanup.any { "parakeet" in it.id })
        assertEquals(setOf("gemma-4-e4b-it-qat-q4_0"), ModelRoles.recommendedIds(cleanup, ModelRole.CLEANUP))
        assertEquals("gemma-4-e4b-it-qat-q4_0", ModelChoices.defaultCleanup(models, "gemma-4-e4b-it-qat-q4_0"))
        assertEquals("gemma-4-e4b-it-qat-q4_0", ModelChoices.defaultCleanup(models, serverCleanup = null))
        assertEquals("qwen_qwen3.5-4b-q4_k_m", ModelChoices.defaultCommand(models))
    }

    @Test
    fun aStockServerIsSplitByNameAndOneFileIsShownOnce() {
        val models = load("stock-mixed.json")
        assertEquals(listOf("whisper-large-v3-turbo"), ids(models, ModelRole.SPEECH))
        assertEquals(listOf("Qwen3.5-4B-Q4_K_M.gguf", "gemma-4-e4b-it", "gemma4:e4b"), ids(models, ModelRole.CLEANUP))
        val commands = ModelRoles.forRole(models, ModelRole.COMMANDS)
        assertEquals(setOf("Qwen3.5-4B-Q4_K_M.gguf"), ModelRoles.recommendedIds(commands, ModelRole.COMMANDS))
        assertEquals(
            setOf("gemma-4-e4b-it"),
            ModelRoles.recommendedIds(ModelRoles.forRole(models, ModelRole.CLEANUP), ModelRole.CLEANUP),
        )
        assertEquals("gemma-4-e4b-it", ModelChoices.defaultCleanup(models, serverCleanup = null))
        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", ModelChoices.defaultCommand(models))
        assertEquals("whisper-large-v3-turbo", ModelChoices.defaultSpeech(models))
    }

    @Test
    fun aSpeechAddressWithoutSpeechNamesKeepsItsWholeList() {
        val models = listOf(OfferedModel("base.en"), OfferedModel("large-v3"))
        assertEquals(listOf("base.en", "large-v3"), ids(models, ModelRole.SPEECH))
        assertEquals(
            emptyList<String>(),
            ids(listOf(OfferedModel("x", roles = setOf(ModelRole.COMMANDS))), ModelRole.SPEECH),
        )
    }

    @Test
    fun unknownRoleNamesAreDroppedAndTheModelArgumentGivesTheFile() {
        val body = """{"data":[{"id":"a","telltype":{"roles":["cleanup","future"]},""" +
            """"status":{"args":["llama-server","-m","/m/A.gguf"]}}]}"""
        assertEquals(listOf(OfferedModel("a", "A.gguf", setOf(ModelRole.CLEANUP))), parseModelList(body))
    }
}
