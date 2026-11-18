package net.jbglenn.telltype.network.check

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.network.NetworkHarness
import net.jbglenn.telltype.network.TestKey
import net.jbglenn.telltype.network.endpoint
import net.jbglenn.telltype.network.gateError
import net.jbglenn.telltype.network.json
import net.jbglenn.telltype.network.pin
import net.jbglenn.telltype.network.plainEndpoint
import net.jbglenn.telltype.network.plainServer
import net.jbglenn.telltype.network.selfSignedCertificate
import net.jbglenn.telltype.network.tlsServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Setup's check of one address (D60): model list, our gate's health, a silent transcription; trust and key. */
class RemoteConnectionCheckerTest {
    private val certificate = selfSignedCertificate()
    private val tls: MockWebServer = tlsServer(certificate)
    private val plain: MockWebServer = plainServer()
    private val harness = NetworkHarness(emptyList(), null)
    private val checker = RemoteConnectionChecker(harness.router, harness.probes, harness.logger)
    private val both = setOf(ServerRole.SPEECH, ServerRole.TEXT)

    @After
    fun close() {
        tls.close()
        plain.close()
    }

    private val healthy = """{"status":"ok","version":"0.2.0",
        "stt":{"ready":true,"model":"parakeet"},"cleanup":{"ready":true,"model":"gemma"},
        "assistant":{"ready":true,"model":"qwen"}}"""

    @Test
    fun `a stock text server over plain HTTP passes by its model list, without a key`() = runTest {
        plain.enqueue(
            json(200, """{"data":[{"id":"qwen3.5-4b","path":"/models/Qwen_Qwen3.5-4B-Q4_K_M.gguf"},{"id":"x"}]}"""),
        )
        plain.enqueue(json(404, "{}"))

        val check = checker.check(ServerCandidate(plain.plainEndpoint(), null, null, setOf(ServerRole.TEXT)))

        check as Outcome.Ok
        assertEquals(mapOf(ServerRole.TEXT to ServerCheckMethod.MODELS), check.value.methods)
        assertEquals(OfferedModel("qwen3.5-4b", "Qwen_Qwen3.5-4B-Q4_K_M.gguf"), check.value.models?.first())
        assertNull(check.value.health)
        val request = plain.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/models", request.url.encodedPath)
        assertNull(request.headers["Authorization"])
        assertTrue(harness.logText().contains("TEXT by models, 2 models listed"))
    }

    @Test
    fun `a speech server without a model list passes by a silent transcription`() = runTest {
        plain.enqueue(json(404, "Not Found"))
        plain.enqueue(json(404, "Not Found"))
        plain.enqueue(json(200, """{"text":""}"""))

        val check = checker.check(ServerCandidate(plain.plainEndpoint(), TestKey, null, setOf(ServerRole.SPEECH)))

        assertEquals(
            mapOf(ServerRole.SPEECH to ServerCheckMethod.SILENT_TRANSCRIPTION),
            (check as Outcome.Ok).value.methods,
        )
        plain.takeRequest()
        plain.takeRequest()
        val upload = plain.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/audio/transcriptions", upload.url.encodedPath)
        assertEquals("Bearer ${TestKey.secret}", upload.headers["Authorization"])
        val body = upload.body!!.utf8()
        assertTrue("a WAV file part", body.contains("RIFF") && body.contains("WAVE"))
        assertTrue("whisper-1 by default", body.contains("whisper-1"))
    }

    @Test
    fun `a text address without a model list fails as models_missing`() = runTest {
        plain.enqueue(json(404, "{}"))
        plain.enqueue(json(404, "{}"))

        val check = checker.check(ServerCandidate(plain.plainEndpoint(), null, null, setOf(ServerRole.TEXT)))

        assertEquals(Outcome.Err(RequestRejected(404, "models_missing")), check)
    }

    @Test
    fun `our gate passes both roles by its health, with its aliases, over the pinned certificate`() = runTest {
        tls.enqueue(gateError(404, "not_found"))
        tls.enqueue(json(200, healthy))

        val check = checker.check(ServerCandidate(tls.endpoint(), TestKey, certificate.pin(), both))

        check as Outcome.Ok
        assertEquals(both.associateWith { ServerCheckMethod.GATE_HEALTH }, check.value.methods)
        assertEquals(listOf("gemma", "qwen"), check.value.models?.map(OfferedModel::id))
        assertEquals("0.2.0", check.value.health?.version)
    }

    @Test
    fun `a self-signed certificate without a pin is not trusted by the system`() = runTest {
        val check = checker.check(ServerCandidate(tls.endpoint(), TestKey, null, both))

        assertEquals(Outcome.Err(CertificateMismatch), check)
        assertEquals("nothing was sent", 0, tls.requestCount)
    }

    @Test
    fun `a refused key fails at once`() = runTest {
        plain.enqueue(json(401, """{"error":{"message":"Invalid API Key","type":"auth"}}"""))

        val check = checker.check(ServerCandidate(plain.plainEndpoint(), TestKey, null, both))

        assertEquals(Outcome.Err(KeyRejected), check)
    }

    @Test
    fun `plain HTTP to a public address is refused before anything is sent`() = runTest {
        val public = ServerEndpoint("203.0.113.7", 80, Scheme.HTTP)

        assertEquals(Outcome.Err(ServerNotConfigured), checker.check(ServerCandidate(public, null, null, both)))
    }

    @Test
    fun `the model catalog lists the text models, or our gate's aliases`() = runTest {
        plain.enqueue(json(200, """{"models":[{"name":"gemma4:12b-qat"}]}"""))
        harness.settings.update {
            it.copy(server = it.server.copy(address = plain.plainEndpoint(), key = null, certificate = null))
        }
        val catalog = RemoteModelCatalog(harness.router, harness.probes)

        assertEquals(Outcome.Ok(listOf(OfferedModel("gemma4:12b-qat"))), catalog.list(ServerRole.TEXT))

        plain.enqueue(json(404, "{}"))
        plain.enqueue(json(200, healthy))
        assertEquals(listOf("gemma", "qwen"), (catalog.list(ServerRole.TEXT) as Outcome.Ok).value.map { it.id })
        plain.enqueue(json(404, "{}"))
        assertEquals(Outcome.Ok(emptyList<OfferedModel>()), catalog.list(ServerRole.SPEECH))
    }
}
