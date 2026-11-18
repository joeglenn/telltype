package net.jbglenn.telltype.data.settings

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.settings.BackendHealth
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.OfferedModels
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerHealth
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brief M1: saved `cleanup`/`assistant` choices become the server's real names after a successful check. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelNameMigrationTest {
    private val endpoint = ServerEndpoint("192.0.2.10", 8170)
    private val settings = FakeSettingsRepository()
    private val health = FakeServerHealthSource()
    private val logger = RecordingLogger()
    private val migration = ModelNameMigration(settings, health, logger)

    private val listed = listOf(
        OfferedModel("gemma-4-e4b-it-qat-q4_0"),
        OfferedModel("qwen_qwen3.5-2b-q4_k_m"),
        OfferedModel("qwen_qwen3.5-4b-q4_k_m"),
    )

    private fun reachable(text: List<OfferedModel>?, cleanup: String? = "gemma-4-e4b-it-qat-q4_0") =
        ServerReachability.Reachable(
            endpoint = endpoint,
            health = cleanup?.let {
                ServerHealth(
                    status = ServerStatus.OK,
                    version = "1.0.0",
                    stt = BackendHealth(ready = true, model = "parakeet", engine = "sherpa-onnx"),
                    cleanup = BackendHealth(ready = true, model = it, engine = null),
                    assistant = BackendHealth(ready = true, model = "qwen_qwen3.5-4b-q4_k_m", engine = null),
                )
            },
            roundTrip = 20.milliseconds,
            checkedAt = Instant.fromEpochMilliseconds(0),
            offered = OfferedModels(speech = null, text = text),
        )

    private suspend fun saved(models: ServerModels) = settings.update {
        it.copy(server = ServerConfig(endpoint, null, null, models = models))
    }

    @Test
    fun theAliasesAreReplacedOnceOnTheNextSuccessfulCheck() = runTest {
        saved(ServerModels(speech = "parakeet", cleanup = "cleanup", command = "assistant"))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { migration.run() }

        health.publish(ServerReachability.Unreachable(ServerUnreachable, Instant.fromEpochMilliseconds(0)))
        assertEquals("cleanup", settings.current().server.models.cleanup)

        health.publish(reachable(listed))
        assertEquals(
            ServerModels(speech = "parakeet", cleanup = "gemma-4-e4b-it-qat-q4_0", command = "qwen_qwen3.5-4b-q4_k_m"),
            settings.current().server.models,
        )
        health.publish(reachable(listed.reversed()))
        assertEquals("one line, for the one change", 1, logger.entries.size)
        assertTrue(logger.entries.single().message.contains("cleanup cleanup -> gemma-4-e4b-it-qat-q4_0"))
    }

    @Test
    fun withoutGateHealthTheRecommendedCleanupModelIsTaken() = runTest {
        saved(ServerModels(cleanup = "cleanup", command = "qwen_qwen3.5-2b-q4_k_m"))
        migration.apply(reachable(listed.reversed(), cleanup = null))
        assertEquals(
            ServerModels(cleanup = "gemma-4-e4b-it-qat-q4_0", command = "qwen_qwen3.5-2b-q4_k_m"),
            settings.current().server.models,
        )
    }

    @Test
    fun anUnknownListOrRealChoicesChangeNothing() = runTest {
        saved(ServerModels(cleanup = "cleanup", command = "assistant"))
        migration.apply(reachable(text = null))
        assertEquals(ServerModels(cleanup = "cleanup", command = "assistant"), settings.current().server.models)
        saved(ServerModels(cleanup = "qwen_qwen3.5-2b-q4_k_m"))
        migration.apply(reachable(listed))
        assertEquals(ServerModels(cleanup = "qwen_qwen3.5-2b-q4_k_m"), settings.current().server.models)
        assertTrue(logger.entries.isEmpty())
    }
}
