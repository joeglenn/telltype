package net.jbglenn.telltype.network.stt

import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.EmptyTranscript
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.error.SpeechEngineFailed
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.network.NetworkHarness
import net.jbglenn.telltype.network.ShortTimeouts
import net.jbglenn.telltype.network.TestKey
import net.jbglenn.telltype.network.endpoint
import net.jbglenn.telltype.network.gateError
import net.jbglenn.telltype.network.json
import net.jbglenn.telltype.network.pin
import net.jbglenn.telltype.network.recordingFile
import net.jbglenn.telltype.network.refusingEndpoint
import net.jbglenn.telltype.network.selfSignedCertificate
import net.jbglenn.telltype.network.tlsServer
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.TestDispatcherProvider
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSpeechToTextEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val certificate: HeldCertificate = selfSignedCertificate()
    private val servers = mutableListOf<MockWebServer>()
    private val transcript = "the quick synthetic fox"

    @After
    fun closeServers() {
        servers.forEach(MockWebServer::close)
    }

    private fun server(cert: HeldCertificate = certificate): MockWebServer = tlsServer(cert).also { servers += it }

    private fun TestScope.engine(harness: NetworkHarness) = RemoteSpeechToTextEngine(
        router = harness.router,
        timeouts = harness.timeouts,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        logger = harness.logger,
    )

    private fun clip(bytes: Int = 32_000) = Fixtures.audioClip(duration = 3.seconds).copy(
        path = recordingFile(folder.root, bytes).absolutePath,
    )

    private fun ok(text: String = transcript) = json(
        200,
        """{"text":"$text","duration":2.5}""",
        "Server-Timing" to "decode;dur=40.1, queue;dur=0.0, stt;dur=167.8, gate;dur=211.1",
    )

    @Test
    fun `a transcription uploads the file as multipart with key, request id and prompt`() = runTest {
        val gate = server().apply { enqueue(ok()) }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
        val audio = clip()

        val outcome = engine(harness).transcribe(audio, SttHints(listOf("Igor", " Osiris ", "Igor", "a,b", " ")))

        assertEquals(
            Outcome.Ok(
                Transcript(
                    transcript,
                    2.5.seconds,
                    listOf(
                        ServerTiming("decode", 40.1.milliseconds),
                        ServerTiming("queue", 0.milliseconds),
                        ServerTiming("stt", 167.8.milliseconds),
                        ServerTiming("gate", 211.1.milliseconds),
                    ),
                ),
            ),
            outcome,
        )
        val request = gate.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
        assertEquals("Bearer ${TestKey.secret}", request.headers["Authorization"])
        assertEquals("session-1", request.headers["X-Request-Id"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val body = request.body!!.utf8()
        assertTrue(body.contains("name=\"file\"; filename=\"recording-1.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
        assertTrue(body.contains("name=\"prompt\""))
        assertTrue(body.contains("\r\n\r\nIgor, Osiris, a b\r\n"))
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("\r\n\r\nen\r\n"))
        assertTrue("the whole file is uploaded", request.bodySize > 32_000)
    }

    @Test
    fun `without hints there is no prompt field and the clip duration fills a missing one`() = runTest {
        val gate = server().apply { enqueue(json(200, """{"text":" hello "}""")) }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        val outcome = engine(harness).transcribe(clip(), SttHints(emptyList()))

        assertEquals(Outcome.Ok(Transcript("hello", 3.seconds, emptyList())), outcome)
        assertFalse(gate.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8().contains("name=\"prompt\""))
    }

    @Test
    fun `a blank transcript is EmptyTranscript`() = runTest {
        val gate = server().apply { enqueue(ok(text = "  ")) }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        assertEquals(Outcome.Err(EmptyTranscript), engine(harness).transcribe(clip(), SttHints(emptyList())))
    }

    @Test
    fun `gate errors map to their error and 4xx is sent once`() = runTest {
        val rows = listOf(
            gateError(401, "unauthorized") to KeyRejected,
            gateError(400, "bad_request") to RequestRejected(400, "bad_request"),
            gateError(413, "too_large") to RequestRejected(413, "too_large"),
            gateError(415, "unsupported_audio") to RequestRejected(415, "unsupported_audio"),
            gateError(429, "busy") to ServerFailed(429, "busy"),
        )
        for ((response, error) in rows) {
            val gate = server().apply { enqueue(response) }
            val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
            assertEquals(Outcome.Err(error), engine(harness).transcribe(clip(), SttHints(emptyList())))
            assertEquals("sent once: $error", 1, gate.requestCount)
        }
    }

    @Test
    fun `502 and 504 are retried once, then reported`() = runTest {
        for ((status, code) in listOf(502 to "backend_error", 504 to "backend_timeout")) {
            val gate = server().apply { repeat(2) { enqueue(gateError(status, code)) } }
            val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
            assertEquals(
                Outcome.Err(ServerFailed(status, code)),
                engine(harness).transcribe(clip(), SttHints(emptyList())),
            )
            assertEquals(2, gate.requestCount)
        }
        val recovering = server().apply {
            enqueue(gateError(502, "backend_error"))
            enqueue(ok())
        }
        val harness = NetworkHarness(listOf(recovering.endpoint()), certificate.pin())
        assertTrue(engine(harness).transcribe(clip(), SttHints(emptyList())) is Outcome.Ok)
    }

    @Test
    fun `503 is retried after Retry-After, then ServerWarmingUp`() = runTest {
        val gate = server().apply { repeat(2) { enqueue(gateError(503, "not_ready", "Retry-After" to "5")) } }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        assertEquals(Outcome.Err(ServerWarmingUp), engine(harness).transcribe(clip(), SttHints(emptyList())))
        assertEquals(2, gate.requestCount)
        assertEquals("waited Retry-After on virtual time", 5_000L, testScheduler.currentTime)
    }

    @Test
    fun `malformed and truncated bodies are MalformedResponse`() = runTest {
        for (body in listOf("""{"text":"cut off""", "<html>ok</html>", """{"txt":"x"}""", "")) {
            val gate = server().apply { enqueue(json(200, body)) }
            val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
            assertEquals(
                "body $body",
                Outcome.Err(MalformedResponse),
                engine(harness).transcribe(clip(), SttHints(emptyList())),
            )
        }
    }

    @Test
    fun `a different certificate is CertificateMismatch and nothing is uploaded`() = runTest {
        val impostor = server(selfSignedCertificate("impostor")).apply { enqueue(ok()) }
        val harness = NetworkHarness(listOf(impostor.endpoint()), certificate.pin())

        assertEquals(Outcome.Err(CertificateMismatch), engine(harness).transcribe(clip(), SttHints(emptyList())))
        assertEquals("the handshake failed before any request", 0, impostor.requestCount)
        assertFalse(harness.logText().contains(TestKey.secret))
    }

    @Test
    fun `an address that does not answer is ServerUnreachable`() = runTest {
        val harness = NetworkHarness(listOf(refusingEndpoint()), certificate.pin())

        assertEquals(Outcome.Err(ServerUnreachable), engine(harness).transcribe(clip(), SttHints(emptyList())))
    }

    @Test
    fun `a stalled response hits the whole-call timeout and is retried once`() = runTest {
        val stalled = MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()
        val gate = server().apply { repeat(2) { enqueue(stalled) } }
        val harness = NetworkHarness(
            listOf(gate.endpoint()),
            certificate.pin(),
            timeouts = ShortTimeouts.copy(transcription = 300.milliseconds),
        )

        assertEquals(Outcome.Err(ServerUnreachable), engine(harness).transcribe(clip(), SttHints(emptyList())))
        assertEquals(2, gate.requestCount)
    }

    @Test
    fun `a slow answer within the timeout still succeeds`() = runTest {
        val slow = ok().newBuilder()
            .headersDelay(300, TimeUnit.MILLISECONDS)
            .bodyDelay(300, TimeUnit.MILLISECONDS)
            .build()
        val gate = server().apply { enqueue(slow) }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        assertTrue(engine(harness).transcribe(clip(), SttHints(emptyList())) is Outcome.Ok)
    }

    @Test
    fun `cancelling mid-upload cancels the call`() = runTest {
        val gate = server().apply { enqueue(ok().newBuilder().onRequestBody(SocketEffect.Stall).build()) }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
        val uploading = CompletableDeferred<Unit>()
        harness.events.onRequestBodyStart = { uploading.complete(Unit) }
        val audio = clip(bytes = 8 * 1024 * 1024)
        var result: Outcome<Transcript, *>? = null

        val job = launch { result = engine(harness).transcribe(audio, SttHints(emptyList())) }
        uploading.await()
        job.cancelAndJoin()

        assertNull("no result after cancellation", result)
        assertTrue(harness.events.events.toString(), harness.events.events.contains("canceled"))
        assertTrue(harness.events.calls.single().isCanceled())
    }

    @Test
    fun `an unreadable recording is not uploaded`() = runTest {
        val gate = server()
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())
        val missing = Fixtures.audioClip().copy(path = folder.root.resolve("gone.wav").absolutePath)

        assertEquals(Outcome.Err(SpeechEngineFailed), engine(harness).transcribe(missing, SttHints(emptyList())))
        assertEquals(0, gate.requestCount)
    }

    @Test
    fun `prepare opens a connection with a model list request and never fails`() = runTest {
        val gate = server().apply { enqueue(json(200, "{}")) }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        engine(harness).prepare()
        engine(
            NetworkHarness(listOf(ServerEndpoint("127.0.0.1", refusingEndpoint().port)), certificate.pin()),
        ).prepare()

        assertEquals("/v1/models", gate.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath)
    }

    @Test
    fun `no key and no transcript text reach the log`() = runTest {
        val gate = server().apply {
            enqueue(gateError(502, "backend_error"))
            enqueue(ok())
        }
        val harness = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

        engine(harness).transcribe(clip(), SttHints(listOf("Igor")))

        val logs = harness.logText()
        assertTrue(logs.contains("session-1"))
        assertFalse(logs.contains(TestKey.secret))
        assertFalse(logs.contains(transcript))
        assertFalse(logs.contains("Igor"))
    }
}
