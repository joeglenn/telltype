package net.jbglenn.telltype.network.cleanup

import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockWebServer
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.ServerTiming
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanedText
import net.jbglenn.telltype.core.model.cleanup.CleanupRequest
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.cleanup.CommandRequest
import net.jbglenn.telltype.core.model.cleanup.EchoSource
import net.jbglenn.telltype.core.model.cleanup.GuardVerdict
import net.jbglenn.telltype.core.model.cleanup.LengthViolation
import net.jbglenn.telltype.core.model.error.CleanupRejected
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerUnreachable
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
import net.jbglenn.telltype.network.NetworkHarness
import net.jbglenn.telltype.network.TestKey
import net.jbglenn.telltype.network.endpoint
import net.jbglenn.telltype.network.gateError
import net.jbglenn.telltype.network.json
import net.jbglenn.telltype.network.pin
import net.jbglenn.telltype.network.refusingEndpoint
import net.jbglenn.telltype.network.selfSignedCertificate
import net.jbglenn.telltype.network.testGuardRules
import net.jbglenn.telltype.network.testPromptSet
import net.jbglenn.telltype.network.tlsServer
import net.jbglenn.telltype.testing.TestDispatcherProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTextCleanerTest {
    private val certificate = selfSignedCertificate()
    private val gate: MockWebServer = tlsServer(certificate)
    private val transcript = "um so lets meet at five no six"
    private val modelText = "Let's meet at six."

    @After
    fun close() = gate.close()

    /** Stands in for the domain functions (A2b): fixed prompt, scripted verdict, extraction = trim. */
    private class StubLogic : CleanupLogic {
        var verdict: (ModelReply) -> GuardVerdict = { GuardVerdict.Accepted(it.content.orEmpty().trim()) }
        val guardInputsSeen = mutableListOf<GuardInputs>()

        override fun buildCleanup(request: CleanupRequest, prompts: PromptSet) = CleanupPrompt(
            chat = ChatPrompt(
                model = prompts.cleanupRequest.model,
                messages = listOf(ChatMessage(ChatRole.SYSTEM, "sys"), ChatMessage(ChatRole.USER, request.transcript)),
                temperature = 0.1,
                maxTokens = 64,
                enableThinking = false,
            ),
            guardInputs = GuardInputs(request.transcript, "sys", "tone", request.dictionary, request.contextBefore),
        )

        override fun buildCommand(request: CommandRequest, prompts: PromptSet) = ChatPrompt(
            model = prompts.commandRequest.model,
            messages = listOf(ChatMessage(ChatRole.USER, request.selection + " / " + request.instruction)),
            temperature = 0.1,
            maxTokens = 128,
            enableThinking = false,
        )

        override fun extract(reply: ModelReply, sentInput: String, rules: GuardRules.ReplyExtraction) =
            reply.content.orEmpty().trim()

        override fun guard(reply: ModelReply, inputs: GuardInputs, rules: GuardRules): GuardVerdict {
            guardInputsSeen += inputs
            return verdict(reply)
        }
    }

    private val logic = StubLogic()

    private fun TestScope.cleaner(harness: NetworkHarness) = RemoteTextCleaner(
        router = harness.router,
        assets = CleanupAssets(testPromptSet(), testGuardRules()),
        logic = logic,
        timeouts = harness.timeouts,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        logger = harness.logger,
    )

    private fun harness() = NetworkHarness(listOf(gate.endpoint()), certificate.pin())

    private val request = CleanupRequest(
        session = SessionId("session-7"),
        transcript = transcript,
        tone = Tone.CASUAL,
        category = AppCategory.PERSONAL_CHAT,
        dictionary = listOf("Igor"),
        contextBefore = "earlier text",
        fieldHint = "Message",
    )

    private fun reply(content: String?, finish: String = "stop") = json(
        200,
        """{"choices":[{"message":{"role":"assistant","content":${content?.let { "\"$it\"" } ?: "null"}},""" +
            """"finish_reason":"$finish"}]}""",
        "Server-Timing" to "llm;dur=276.2, gate;dur=276.3",
    )

    @Test
    fun `an accepted reply is APPLIED text with the server timings`() = runTest {
        gate.enqueue(reply(modelText))
        val harness = harness()

        val outcome = cleaner(harness).clean(request)

        assertEquals(
            Outcome.Ok(
                CleanedText(
                    modelText,
                    CleanupStatus.APPLIED,
                    snippetOnly = false,
                    serverTimings = listOf(
                        ServerTiming("llm", 276.2.milliseconds),
                        ServerTiming("gate", 276.3.milliseconds),
                    ),
                ),
            ),
            outcome,
        )
        val sent = gate.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", sent.url.encodedPath)
        assertEquals("Bearer ${TestKey.secret}", sent.headers["Authorization"])
        assertEquals("session-7", sent.headers["X-Request-Id"])
        val body = Json.parseToJsonElement(sent.body!!.utf8()) as JsonObject
        assertEquals("cleanup", body["model"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals(transcript, logic.guardInputsSeen.single().transcript)
    }

    @Test
    fun `a guard rejection is CleanupRejected, never text`() = runTest {
        gate.enqueue(reply("Sure! Here is your text"))
        logic.verdict = { GuardVerdict.Echo(EchoSource.SYSTEM_PROMPT) }

        val outcome = cleaner(harness()).clean(request)

        assertEquals(Outcome.Err(CleanupRejected(GuardVerdict.Echo(EchoSource.SYSTEM_PROMPT))), outcome)
    }

    @Test
    fun `cleanup is never retried`() = runTest {
        repeat(2) { gate.enqueue(gateError(502, "backend_error")) }

        assertEquals(Outcome.Err(ServerFailed(502, "backend_error")), cleaner(harness()).clean(request))
        assertEquals(1, gate.requestCount)
    }

    @Test
    fun `network failures are cleanup errors`() = runTest {
        gate.enqueue(gateError(401, "unauthorized"))
        assertEquals(Outcome.Err(KeyRejected), cleaner(harness()).clean(request))

        gate.enqueue(json(200, """{"choices":[]}"""))
        assertEquals(Outcome.Err(MalformedResponse), cleaner(harness()).clean(request))

        val unreachable = NetworkHarness(listOf(refusingEndpoint()), certificate.pin())
        assertEquals(Outcome.Err(ServerUnreachable), cleaner(unreachable).clean(request))
    }

    @Test
    fun `a command reply is extracted and applied`() = runTest {
        gate.enqueue(reply("Shorter text."))
        val command =
            CommandRequest(SessionId("session-8"), selection = "A long text. ", instruction = "make it shorter")

        val outcome = cleaner(harness()).command(command)

        assertEquals(Outcome.Ok(CleanedText("Shorter text.", CleanupStatus.APPLIED, false, outcome.timings())), outcome)
        val body = Json.parseToJsonElement(gate.takeRequest(1, TimeUnit.SECONDS)!!.body!!.utf8()) as JsonObject
        assertEquals("assistant", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty or cut-off command reply is rejected`() = runTest {
        val command = CommandRequest(SessionId("session-8"), selection = "Some text", instruction = "rewrite")
        gate.enqueue(reply(null))
        assertEquals(Outcome.Err(CleanupRejected(GuardVerdict.EmptyReply)), cleaner(harness()).command(command))

        gate.enqueue(reply("Half of the new", finish = "length"))
        assertEquals(
            Outcome.Err(CleanupRejected(GuardVerdict.LengthOutOfBounds(LengthViolation.CUT_OFF, 4, null))),
            cleaner(harness()).command(command),
        )
    }

    @Test
    fun `no dictated text, context or key reaches the log`() = runTest {
        gate.enqueue(reply(modelText))
        val harness = harness()
        cleaner(harness).clean(request)
        gate.enqueue(reply("x"))
        logic.verdict = { GuardVerdict.EmptyReply }
        cleaner(harness).clean(request)

        val logs = harness.logText()
        assertTrue(logs.contains("guard empty"))
        for (secret in listOf(TestKey.secret, transcript, modelText, "earlier text", "Igor")) {
            assertFalse("logged: $secret", logs.contains(secret))
        }
    }

    private fun Outcome<CleanedText, *>.timings(): List<ServerTiming> = (this as Outcome.Ok).value.serverTimings
}
