package net.jbglenn.telltype.network.route

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.network.NetworkHarness
import net.jbglenn.telltype.network.TestKey
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.failure.TransportFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One address per role (D60): retry once when safe, a breaker per address, plain HTTP only to private hosts. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRouterTest {
    private val speech = ServerEndpoint("192.0.2.10", 8170)
    private val text = ServerEndpoint("192.168.1.11", 8080, Scheme.HTTP)
    private val harness = NetworkHarness(listOf(speech), null)
    private val router = harness.router
    private val seen = mutableListOf<String>()

    private fun answering(vararg results: Attempt<String>): suspend (GateTarget) -> Attempt<String> {
        val queue = ArrayDeque(results.toList())
        return { target ->
            seen += target.endpoint.baseUrl
            queue.removeFirstOrNull() ?: Attempt.Success("ok")
        }
    }

    private val busy = Attempt.Failure(CallFailure.Http(503, "not_ready", 2.seconds))
    private val refused = Attempt.Failure(CallFailure.Transport(TransportFailure.REFUSED, connected = false))

    @Test
    fun `a transcription is retried once after a 503, after its Retry-After`() = runTest {
        val outcome = router.execute(ServerRole.SPEECH, CallPolicy.TRANSCRIPTION, answering(busy))

        assertEquals(Outcome.Ok("ok"), outcome)
        assertEquals(listOf(speech.baseUrl, speech.baseUrl), seen)
        assertEquals(2_000L, currentTime)
    }

    @Test
    fun `a second failure is the answer, and chat is never retried`() = runTest {
        assertEquals(
            Outcome.Err(ServerWarmingUp),
            router.execute(ServerRole.SPEECH, CallPolicy.TRANSCRIPTION, answering(busy, busy)),
        )
        seen.clear()
        assertEquals(Outcome.Err(ServerWarmingUp), router.execute(ServerRole.TEXT, CallPolicy.CHAT, answering(busy)))
        assertEquals(1, seen.size)
    }

    @Test
    fun `text goes to the text model address with the key`() = runTest {
        harness.settings.update { it.copy(server = ServerConfig(speech, TestKey, null, textAddress = text)) }
        var key: Any? = null

        router.execute(ServerRole.TEXT, CallPolicy.CHAT) { target ->
            key = target.key
            seen += target.endpoint.baseUrl
            Attempt.Success("ok")
        }

        assertEquals(listOf("http://192.168.1.11:8080"), seen)
        assertEquals(TestKey, key)
    }

    @Test
    fun `three address failures open the breaker, a health probe ignores it`() = runTest {
        repeat(3) { router.execute(ServerRole.SPEECH, CallPolicy.CHAT, answering(refused)) }
        seen.clear()

        assertEquals(Outcome.Err(ServerUnreachable), router.execute(ServerRole.SPEECH, CallPolicy.CHAT, answering()))
        assertTrue("skipped", seen.isEmpty())
        assertEquals(Outcome.Ok("ok"), router.execute(ServerRole.SPEECH, CallPolicy.HEALTH, answering()))
    }

    @Test
    fun `plain HTTP to a public address and no address at all are refused before any call`() = runTest {
        harness.settings.update {
            it.copy(server = ServerConfig(ServerEndpoint("203.0.113.9", 80, Scheme.HTTP), null, null))
        }
        assertEquals(Outcome.Err(ServerNotConfigured), router.execute(ServerRole.SPEECH, CallPolicy.CHAT, answering()))
        harness.settings.update { it.copy(server = ServerConfig.Unconfigured) }
        assertEquals(Outcome.Err(ServerNotConfigured), router.execute(ServerRole.TEXT, CallPolicy.CHAT, answering()))
        assertTrue(seen.isEmpty())
        assertTrue(harness.logText().contains("plain HTTP to a public address refused"))
    }
}
