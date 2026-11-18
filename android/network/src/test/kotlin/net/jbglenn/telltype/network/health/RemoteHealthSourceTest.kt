package net.jbglenn.telltype.network.health

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.network.NetworkHarness
import net.jbglenn.telltype.network.TestKey
import net.jbglenn.telltype.network.endpoint
import net.jbglenn.telltype.network.gateError
import net.jbglenn.telltype.network.json
import net.jbglenn.telltype.network.pin
import net.jbglenn.telltype.network.plainEndpoint
import net.jbglenn.telltype.network.plainServer
import net.jbglenn.telltype.network.refusingEndpoint
import net.jbglenn.telltype.network.selfSignedCertificate
import net.jbglenn.telltype.network.tlsServer
import net.jbglenn.telltype.testing.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D60: the server's state from `/v1/models` (every server) and our gate's `/healthz` (when it answers). */
class RemoteHealthSourceTest {
    private val certificate = selfSignedCertificate()
    private val gate: MockWebServer = tlsServer(certificate)
    private val text: MockWebServer = plainServer()

    @After
    fun close() {
        gate.close()
        text.close()
    }

    private val healthy = """{"status":"ok","version":"0.1.0",
        "stt":{"ready":true,"model":"parakeet-unified-en-0.6b-int8","engine":"sherpa-onnx"},
        "cleanup":{"ready":true,"model":"gemma-4-e4b-it-qat-q4_0"},
        "assistant":{"ready":true,"model":"qwen3.5-4b-q4_k_m"}}"""

    private val models = """{"object":"list","data":[{"id":"Qwen_Qwen3.5-2B-Q4_K_M.gguf","object":"model"}]}"""

    private fun source(harness: NetworkHarness) =
        RemoteHealthSource(harness.settings, harness.router, harness.probes, harness.clock, harness.logger)

    @Test
    fun `before a probe reachability is Unknown`() {
        assertEquals(
            ServerReachability.Unknown,
            source(NetworkHarness(listOf(gate.endpoint()), certificate.pin())).reachability.value,
        )
    }

    @Test
    fun `our gate without a model list is Reachable with its health and its aliases for text`() = runTest {
        gate.enqueue(gateError(404, "not_found"))
        gate.enqueue(json(200, healthy))
        val source = source(NetworkHarness(listOf(gate.endpoint()), certificate.pin()))

        val result = source.probe() as ServerReachability.Reachable

        assertEquals(gate.endpoint(), result.endpoint)
        assertEquals(ServerStatus.OK, result.health?.status)
        assertNull(result.offered.speech)
        assertEquals(listOf("gemma-4-e4b-it-qat-q4_0", "qwen3.5-4b-q4_k_m"), result.offered.text?.map(OfferedModel::id))
        assertEquals(Fixtures.Epoch, result.checkedAt)
        assertEquals(result, source.reachability.value)
        val first = gate.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/models", first.url.encodedPath)
        assertEquals("Bearer ${TestKey.secret}", first.headers["Authorization"])
        assertEquals("/healthz", gate.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun `a stock server and a separate plain-HTTP text address, no key`() = runTest {
        gate.enqueue(json(200, """{"data":[{"id":"whisper-1"}]}"""))
        gate.enqueue(json(404, """{"detail":"Not Found"}"""))
        text.enqueue(json(200, models))
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
        harness.settings.update {
            it.copy(server = ServerConfig(gate.endpoint(), null, certificate.pin(), textAddress = text.plainEndpoint()))
        }

        val result = source(harness).probe() as ServerReachability.Reachable

        assertNull(result.health)
        assertEquals(text.plainEndpoint(), result.textEndpoint)
        assertEquals(listOf("whisper-1"), result.offered.speech?.map(OfferedModel::id))
        assertEquals(listOf("Qwen_Qwen3.5-2B-Q4_K_M.gguf"), result.offered.text?.map(OfferedModel::id))
        assertNull("no key, no Authorization header", text.takeRequest(1, TimeUnit.SECONDS)!!.headers["Authorization"])
        assertTrue(harness.logText().contains("text via ${text.plainEndpoint().baseUrl}"))
    }

    @Test
    fun `failures are Unreachable with the error`() = runTest {
        val rows = listOf(
            listOf(gateError(404, "not_found"), gateError(503, "not_ready", "Retry-After" to "5")) to ServerWarmingUp,
            listOf(gateError(401, "unauthorized")) to KeyRejected,
            listOf(json(403, """{"error":"forbidden"}""")) to KeyRejected,
        )
        for ((responses, error) in rows) {
            responses.forEach(gate::enqueue)
            val result = source(NetworkHarness(listOf(gate.endpoint()), certificate.pin())).probe()
            assertEquals(ServerReachability.Unreachable(error, Fixtures.Epoch), result)
        }
        val down = source(NetworkHarness(listOf(refusingEndpoint()), certificate.pin()))
        assertEquals(ServerReachability.Unreachable(ServerUnreachable, Fixtures.Epoch), down.probe())
    }

    @Test
    fun `a text address that does not answer makes the server unreachable`() = runTest {
        gate.enqueue(json(200, models))
        gate.enqueue(json(404, "{}"))
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
        harness.settings.update { it.copy(server = it.server.copy(textAddress = refusingEndpoint())) }

        assertEquals(ServerReachability.Unreachable(ServerUnreachable, Fixtures.Epoch), source(harness).probe())
    }

    @Test
    fun `the probe log names the address but not the key`() = runTest {
        gate.enqueue(json(404, "{}"))
        gate.enqueue(json(200, healthy))
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        source(harness).probe()

        assertTrue(harness.logText().contains("reachable via ${gate.endpoint().baseUrl}, gate ok"))
        assertFalse(harness.logText().contains(TestKey.secret))
    }
}
