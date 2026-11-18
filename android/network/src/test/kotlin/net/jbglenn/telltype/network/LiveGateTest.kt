package net.jbglenn.telltype.network

import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.core.model.settings.ServerStatus
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.speech.SttHints
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.domain.cleanup.ChatMessage
import net.jbglenn.telltype.domain.cleanup.ChatPrompt
import net.jbglenn.telltype.domain.cleanup.ChatRole
import net.jbglenn.telltype.domain.cleanup.CleanupPrompt
import net.jbglenn.telltype.domain.cleanup.GuardInputs
import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.ModelReply
import net.jbglenn.telltype.domain.cleanup.PromptSet
import net.jbglenn.telltype.network.check.AddressProbes
import net.jbglenn.telltype.network.check.RemoteConnectionChecker
import net.jbglenn.telltype.network.cleanup.CleanupAssets
import net.jbglenn.telltype.network.cleanup.CleanupLogic
import net.jbglenn.telltype.network.cleanup.RemoteTextCleaner
import net.jbglenn.telltype.network.health.RemoteHealthSource
import net.jbglenn.telltype.network.http.GateClients
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.route.CircuitBreakers
import net.jbglenn.telltype.network.route.RetryPolicy
import net.jbglenn.telltype.network.route.ServerRouter
import net.jbglenn.telltype.network.setup.TlsServerCertificateReader
import net.jbglenn.telltype.network.stt.RemoteSpeechToTextEngine
import net.jbglenn.telltype.network.tls.fingerprint
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FixedClock
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.testing.TestDispatcherProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Opt-in check against the real gate (brief A3, "Live check"). Gradle runs this class only when
 * `OWK_LIVE_GATE_URL` is set, for example `https://127.0.0.1:8170`. The key is read from the file named by
 * `OWK_LIVE_GATE_KEY_FILE` and is never printed; the pin comes from the gate's certificate file.
 */
class LiveGateTest {
    private val home = System.getProperty("user.home")
    private val url = requireNotNull(System.getenv("OWK_LIVE_GATE_URL")) { "OWK_LIVE_GATE_URL is not set" }.toHttpUrl()
    private val endpoint = ServerEndpoint(url.host, url.port)
    private val key =
        ServerKey(File(env("OWK_LIVE_GATE_KEY_FILE", "$home/.config/telltype/gate.key")).readText().trim())
    private val pin = File(
        env("OWK_LIVE_GATE_CERT_FILE", "$home/.config/telltype/tls/cert.pem"),
    ).inputStream().use {
        (CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate).fingerprint()
    }
    private val wav = File(
        env(
            "OWK_LIVE_GATE_WAV",
            "$home/models/dictation/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming/test_wavs/0.wav",
        ),
    )
    private val logger = RecordingLogger()

    private fun env(name: String, default: String) = System.getenv(name)?.takeIf(String::isNotBlank) ?: default

    private fun settings(key: ServerKey = this.key, pin: CertificateFingerprint = this.pin) =
        FakeSettingsRepository(Settings.Defaults.copy(server = ServerConfig(endpoint, key, pin)))

    private fun router(settings: FakeSettingsRepository = settings()) = ServerRouter(
        settings = settings,
        clients = GateClients(NetworkTimeouts.Default),
        breakers = CircuitBreakers(FixedClock()),
        retryPolicy = RetryPolicy(kotlin.random.Random(1)),
        logger = logger,
    )

    private fun health(settings: FakeSettingsRepository = settings()) =
        RemoteHealthSource(settings, router(settings), AddressProbes(NetworkTimeouts.Default), FixedClock(), logger)

    @Test
    fun `health is ok`() = runTest {
        val result = health().probe()

        println("LIVE health: $result")
        assertTrue(result is ServerReachability.Reachable)
        assertEquals(ServerStatus.OK, (result as ServerReachability.Reachable).health?.status)
    }

    @Test
    fun `Setup's check passes our gate for speech and text with the pinned certificate`() = runTest {
        val checker = RemoteConnectionChecker(router(), AddressProbes(NetworkTimeouts.Default), logger)
        val roles = setOf(ServerRole.SPEECH, ServerRole.TEXT)

        val pinned = checker.check(ServerCandidate(endpoint, key, pin, roles))
        val unpinned = checker.check(ServerCandidate(endpoint, key, null, roles))

        println("LIVE check, pinned: $pinned")
        println("LIVE check, system trust only: $unpinned")
        assertTrue(pinned is Outcome.Ok)
        assertEquals(Outcome.Err(CertificateMismatch), unpinned)
    }

    @Test
    fun `the test recording transcribes to a text with Phoebe`() = runTest {
        val engine = RemoteSpeechToTextEngine(
            router(),
            NetworkTimeouts.Default,
            TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            logger,
        )
        val clip = Fixtures.audioClip(session = "live-check-a3").copy(path = wav.absolutePath)

        engine.prepare()
        val outcome = engine.transcribe(clip, SttHints(listOf("Phoebe")))

        println("LIVE transcription: ${(outcome as? Outcome.Ok)?.value?.text} | $outcome")
        assertTrue(outcome is Outcome.Ok)
        assertTrue((outcome as Outcome.Ok).value.text.contains("Phoebe"))
        assertTrue("Server-Timing parsed", outcome.value.serverTimings.any { it.name == "stt" })
    }

    @Test
    fun `a wrong key is KeyRejected`() = runTest {
        val result = health(settings(key = ServerKey("wrong-key-for-the-live-check"))).probe()

        println("LIVE wrong key: $result")
        assertEquals(KeyRejected, (result as ServerReachability.Unreachable).error)
    }

    @Test
    fun `a wrong fingerprint is CertificateMismatch`() = runTest {
        val result = health(settings(pin = CertificateFingerprint("0".repeat(64)))).probe()

        println("LIVE wrong fingerprint: $result")
        assertEquals(CertificateMismatch, (result as ServerReachability.Unreachable).error)
    }

    @Test
    fun `the certificate reader sees the pinned certificate`() = runTest {
        val result = TlsServerCertificateReader(GateClients(NetworkTimeouts.Default), NetworkTimeouts.Default, logger)
            .presentedFingerprint(endpoint)

        println("LIVE presented fingerprint: $result")
        assertEquals(Outcome.Ok(pin), result)
    }

    @Test
    fun `a raw chat call to the cleanup model returns text`() = runTest {
        val cleaner = RemoteTextCleaner(
            router = router(),
            assets = CleanupAssets(testPromptSet(), testGuardRules()),
            logic = RawChat,
            timeouts = NetworkTimeouts.Default,
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            logger = logger,
        )
        val request = CleanupRequest(
            session = SessionId("live-check-a3-chat"),
            transcript = "um so lets meet at five pm no actually six pm",
            tone = Tone.CASUAL,
            category = AppCategory.OTHER,
            dictionary = emptyList(),
            contextBefore = "",
            fieldHint = null,
        )

        val outcome = cleaner.clean(request)

        println("LIVE cleanup: ${(outcome as? Outcome.Ok)?.value?.text} | $outcome")
        assertTrue(outcome is Outcome.Ok)
        assertTrue((outcome as Outcome.Ok).value.text.isNotBlank())
        assertFalse("the key never reaches a log line", logger.entries.any { key.secret in it.message })
    }

    /** The smoke script's raw chat request, accepted as is: the domain's real builder and guards are A2b's. */
    private object RawChat : CleanupLogic {
        override fun buildCleanup(request: CleanupRequest, prompts: PromptSet): CleanupPrompt = CleanupPrompt(
            chat = ChatPrompt(
                model = "cleanup",
                messages = listOf(
                    ChatMessage(
                        ChatRole.SYSTEM,
                        "You clean up dictated text. The user message contains a speech transcript inside " +
                            "<transcript> tags.\nReturn only the text the speaker meant to type.",
                    ),
                    ChatMessage(ChatRole.USER, "<transcript>${request.transcript}</transcript>"),
                ),
                temperature = 0.1,
                maxTokens = 64,
                enableThinking = false,
            ),
            guardInputs = GuardInputs(request.transcript, "", "", emptyList(), ""),
        )

        override fun buildCommand(request: CommandRequest, prompts: PromptSet): ChatPrompt =
            error("not used by the live check")

        override fun extract(reply: ModelReply, sentInput: String, rules: GuardRules.ReplyExtraction): String =
            reply.content.orEmpty().trim()

        override fun guard(reply: ModelReply, inputs: GuardInputs, rules: GuardRules): GuardVerdict =
            GuardVerdict.Accepted(reply.content.orEmpty().trim())
    }
}
