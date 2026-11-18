package net.jbglenn.telltype.ui.screens.settings

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.testing.FakeServerModelCatalog
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.ui.designsystem.R as DesignR
import net.jbglenn.telltype.ui.screens.MainDispatcherRule
import net.jbglenn.telltype.ui.screens.keepCollecting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Settings → Models (D58, D60). */
class ModelsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val catalog = FakeServerModelCatalog()

    private suspend fun configured(models: ServerModels) = settings.update {
        it.copy(server = ServerConfig(ServerEndpoint("192.0.2.10", 8170), null, null, models = models))
    }

    @Test
    fun listsTheTextModelsWithTheRecommendedMarkAndSavesAChoice() = runTest {
        configured(ServerModels(cleanup = "qwen3.5-2b"))
        catalog.listCall.answers { role ->
            Outcome.Ok(
                if (role == ServerRole.TEXT) {
                    listOf(OfferedModel("qwen3.5-2b"), OfferedModel("x", file = "Qwen3.5-9B-Q4_K_M.gguf"))
                } else {
                    emptyList()
                },
            )
        }
        val vm = ModelsViewModel(settings, catalog)
        keepCollecting(vm.state)

        val state = vm.state.value
        assertEquals(listOf(ModelOption("qwen3.5-2b", false), ModelOption("x", true)), state.command)
        assertEquals(
            "cleanup marks Gemma 4 E4B, not the command models",
            listOf(ModelOption("qwen3.5-2b", false), ModelOption("x", false)),
            state.cleanup,
        )
        assertEquals("speech is typed: the address lists nothing", emptyList<ModelOption>(), state.speech)
        assertFalse(state.isMissing(ModelKind.CLEANUP))

        vm.onAction(ModelsAction.Choose(ModelKind.COMMAND, "x"))
        vm.onAction(ModelsAction.Choose(ModelKind.SPEECH, "  "))

        assertEquals(
            ServerModels(speech = null, cleanup = "qwen3.5-2b", command = "x"),
            settings.current().server.models,
        )
    }

    @Test
    fun aChoiceTheServerNoLongerListsIsMissing() = runTest {
        configured(ServerModels(command = "gone"))
        val vm = ModelsViewModel(settings, catalog)
        keepCollecting(vm.state)

        assertTrue(vm.state.value.isMissing(ModelKind.COMMAND))
        assertFalse(vm.state.value.isMissing(ModelKind.CLEANUP))
    }

    @Test
    fun anUnreachableServerSaysSoAndRefreshReadsAgain() = runTest {
        configured(ServerModels.Unset)
        catalog.listCall.returns(Outcome.Err(ServerUnreachable))
        val vm = ModelsViewModel(settings, catalog)
        keepCollecting(vm.state)
        assertEquals(DesignR.string.connection_unreachable, vm.state.value.problem)

        catalog.listCall.returns(Outcome.Ok(emptyList()))
        vm.onAction(ModelsAction.Refresh)

        assertNull(vm.state.value.problem)
        assertEquals(4, catalog.listCall.calls.size)
    }

    @Test
    fun withoutAServerNothingIsAsked() = runTest {
        val vm = ModelsViewModel(settings, catalog)
        keepCollecting(vm.state)

        assertFalse(vm.state.value.configured)
        assertTrue(catalog.listCall.calls.isEmpty())
    }
}
