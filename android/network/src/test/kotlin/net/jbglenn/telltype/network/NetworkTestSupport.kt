package net.jbglenn.telltype.network

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.MaxTokensRule
import net.jbglenn.telltype.domain.cleanup.ModelRequestSettings
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.network.check.AddressProbes
import net.jbglenn.telltype.network.http.GateClients
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.route.CircuitBreakers
import net.jbglenn.telltype.network.route.RetryPolicy
import net.jbglenn.telltype.network.route.ServerRouter
import net.jbglenn.telltype.network.tls.fingerprint
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FixedClock
import net.jbglenn.telltype.testing.RecordingLogger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A synthetic key: never a real one. Tests assert it never reaches a log line. */
internal val TestKey = ServerKey("test-key-0123456789-not-a-real-secret")

/** One self-signed certificate per test class run, like the gate's own. */
internal fun selfSignedCertificate(name: String = "telltype-gate"): HeldCertificate =
    HeldCertificate.Builder().commonName(name).addSubjectAlternativeName("127.0.0.1").ecdsa256().build()

/** A MockWebServer on 127.0.0.1 speaking TLS with [certificate]. Close it after the test. */
internal fun tlsServer(certificate: HeldCertificate): MockWebServer {
    val server = MockWebServer()
    server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory())
    server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    return server
}

/** The gate address of [server]. */
internal fun MockWebServer.endpoint(): ServerEndpoint = ServerEndpoint("127.0.0.1", port)

/** An address where nothing listens: connecting is refused at once. */
internal fun refusingEndpoint(): ServerEndpoint {
    val port = ServerSocket(0).use { it.localPort }
    return ServerEndpoint("127.0.0.1", port)
}

/** The fingerprint the app would pin for [certificate]. */
internal fun HeldCertificate.pin(): CertificateFingerprint = certificate.fingerprint()

/** Settings whose server address is the first of [endpoints], with [TestKey] and [pin]. */
internal fun settingsFor(endpoints: List<ServerEndpoint>, pin: CertificateFingerprint?): FakeSettingsRepository =
    FakeSettingsRepository(
        Settings.Defaults.copy(
            server = ServerConfig(address = endpoints.firstOrNull(), key = TestKey, certificate = pin),
        ),
    )

/** A plain-HTTP MockWebServer on 127.0.0.1, like a stock server on the home network (D60). Close it after the test. */
internal fun plainServer(): MockWebServer {
    val server = MockWebServer()
    server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
    return server
}

/** The plain-HTTP address of [server]. */
internal fun MockWebServer.plainEndpoint(): ServerEndpoint = ServerEndpoint("127.0.0.1", port, Scheme.HTTP)

/** Short timeouts so the stalled-response tests finish quickly (OkHttp's timers are wall-clock). */
internal val ShortTimeouts = NetworkTimeouts(
    connect = 2.seconds,
    connectRetry = 2.seconds,
    write = 2.seconds,
    read = 2.seconds,
    health = 2.seconds,
    transcription = 2.seconds,
    cleanup = 2.seconds,
    command = 2.seconds,
    certificateRead = 2.seconds,
)

/** Records OkHttp call events so tests can see cancellation and request-body progress. */
internal class CallEvents : EventListener() {
    val events: MutableList<String> = CopyOnWriteArrayList()
    val calls: MutableList<Call> = CopyOnWriteArrayList()

    /** Called when a request body starts uploading. */
    @Volatile
    var onRequestBodyStart: () -> Unit = {}

    override fun callStart(call: Call) {
        calls += call
        events += "callStart"
    }

    override fun requestBodyStart(call: Call) {
        events += "requestBodyStart"
        onRequestBodyStart()
    }

    override fun canceled(call: Call) {
        events += "canceled"
    }

    override fun callFailed(call: Call, ioe: java.io.IOException) {
        events += "callFailed"
    }

    override fun callEnd(call: Call) {
        events += "callEnd"
    }
}

/** Everything a component under test needs, wired like the Hilt module does. */
internal class NetworkHarness(
    endpoints: List<ServerEndpoint>,
    pin: CertificateFingerprint?,
    val timeouts: NetworkTimeouts = ShortTimeouts,
    retryAfterCap: Duration = 5.seconds,
) {
    val events = CallEvents()
    val clock = FixedClock()
    val logger = RecordingLogger()
    val settings = settingsFor(endpoints, pin)
    val clients = GateClients(timeouts, eventListenerFactory = { events })
    val breakers = CircuitBreakers(clock)
    val router =
        ServerRouter(settings, clients, breakers, RetryPolicy(Random(seed = 7), maxRetryAfter = retryAfterCap), logger)
    val probes = AddressProbes(timeouts)

    /** Every log message so far, joined, for "never logged" assertions. */
    fun logText(): String = logger.entries.joinToString("\n") { it.message + " " + it.throwable }
}

/** A JSON response with [code]. */
internal fun json(code: Int, body: String, vararg headers: Pair<String, String>): MockResponse {
    val builder = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body)
    headers.forEach { (name, value) -> builder.addHeader(name, value) }
    return builder.build()
}

/** A gate error body (Appendix C). */
internal fun gateError(code: Int, errorCode: String, vararg headers: Pair<String, String>): MockResponse =
    json(code, """{"error":{"code":"$errorCode","message":"synthetic"}}""", *headers)

/** A WAV-sized file of synthetic bytes in [dir]. */
internal fun recordingFile(dir: File, bytes: Int = 32_000, name: String = "recording-1.wav"): File =
    File(dir, name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

/** A [GuardRules] with the shipped values the network code reads; the rest are placeholders. */
internal fun testGuardRules(): GuardRules = GuardRules(
    version = 2,
    order = net.jbglenn.telltype.core.model.cleanup.Guard.entries,
    replyExtraction = GuardRules.ReplyExtraction("<think>", "</think>", emptyList(), listOf("\"" to "\"")),
    normalisation = GuardRules.Normalisation("NFKC", emptyList(), ".,:/-", emptyList(), emptyList(), 2),
    assistantPhrasing = GuardRules.AssistantPhrasing(emptyList(), emptyList()),
    length = GuardRules.Length(3.0, 8, 0.3, 4, listOf("length")),
    unspokenWords = GuardRules.UnspokenWords(
        allowanceRatio = 0.2,
        ignoreSingleCharacterTokens = true,
        freeWords = emptyList(),
        equivalents = emptyList(),
        contractions = GuardRules.Contractions(emptyMap(), "'s", emptyMap()),
        numberWords = GuardRules.NumberWords(
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyMap(),
            emptyList(),
            8,
        ),
    ),
    echo = GuardRules.Echo(6, 6, true, listOf("transcript")),
    snippetMarkers = GuardRules.SnippetMarkers("⟦", "S", "⟧", 3, "⟦S[0-9]{1,3}⟧"),
    fallback = GuardRules.Fallback(emptyList(), "", ".!?", ",;:", listOf("i"), emptyList(), emptyList(), emptyList()),
)

/** A [PromptSet] with placeholder texts; the cleaner hands it to the (stubbed) prompt builder only. */
internal fun testPromptSet(): PromptSet {
    val tokens = MaxTokensRule(charsPerToken = 3, ratio = 1.5, margin = 32, min = 64, max = 2048)
    return PromptSet(
        version = 2,
        cleanupSystem = "system",
        cleanupUserTemplate = "<transcript>{{transcript}}</transcript>",
        commandSystem = "command system",
        commandUserTemplate = "<selection>{{selection}}</selection><instruction>{{instruction}}</instruction>",
        toneLines = Tone.entries.associateWith { it.id },
        categoryLabels = AppCategory.entries.associateWith { it.id },
        cleanupRequest = ModelRequestSettings("cleanup", 0.1, enableThinking = false, maxTokens = tokens),
        commandRequest = ModelRequestSettings("assistant", 0.1, enableThinking = false, maxTokens = tokens),
        contextBeforeMaxChars = 500,
    )
}
