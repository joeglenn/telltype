package net.jbglenn.telltype.network.http

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.network.ShortTimeouts
import net.jbglenn.telltype.network.endpoint
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.failure.TransportFailure
import net.jbglenn.telltype.network.failure.isConnectFailure
import net.jbglenn.telltype.network.json
import net.jbglenn.telltype.network.pin
import net.jbglenn.telltype.network.refusingEndpoint
import net.jbglenn.telltype.network.route.Attempt
import net.jbglenn.telltype.network.selfSignedCertificate
import net.jbglenn.telltype.network.tlsServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clients' connect timeouts, and whether a failure is recognised as one before or after connecting. */
class GateClientsTest {
    private val certificate = selfSignedCertificate()
    private val gate: MockWebServer = tlsServer(certificate)
    private val clients = GateClients(ShortTimeouts.copy(connect = 700.milliseconds, connectRetry = 3.seconds))
    private val client = clients.pinned(certificate.pin()).firstTry

    @After
    fun close() = gate.close()

    private suspend fun healthAt(endpoint: ServerEndpoint): Attempt<Unit> = exchange(
        client,
        Request.Builder().url(endpoint.baseUrl + GateRoute.HEALTH.path).build(),
        timeout = 500.milliseconds,
    ) { response -> readGateResponse(response) { _, _ -> Unit } }

    private fun Attempt<Unit>.transport() = ((this as Attempt.Failure).failure as CallFailure.Transport)

    @Test
    fun `the first try connects with the short timeout, the retry and setup with the patient one`() {
        val pinned = clients.pinned(certificate.pin())

        assertEquals(700, pinned.firstTry.connectTimeoutMillis)
        assertEquals(3_000, pinned.retry.connectTimeoutMillis)
        assertEquals(3_000, clients.capturing().connectTimeoutMillis)
        assertSame("one connection pool", pinned.firstTry.connectionPool, pinned.retry.connectionPool)
        assertEquals("read timeout unchanged", 2_000, pinned.retry.readTimeoutMillis)
        assertSame("cached per pin", pinned, clients.pinned(certificate.pin()))
    }

    @Test
    fun `a refused connection is a failure before connecting`() = runTest {
        val failure = healthAt(refusingEndpoint()).transport()

        assertEquals(TransportFailure.REFUSED, failure.kind)
        assertFalse(failure.connected)
        assertTrue(isConnectFailure(failure))
    }

    @Test
    fun `a listener that never answers the TLS handshake is a failure before connecting`() = runTest {
        // The kernel completes the TCP handshake for the backlog; nobody ever speaks TLS.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { silent ->
            val quickRead = GateClients(ShortTimeouts.copy(read = 300.milliseconds))
            val request = Request.Builder().url("https://127.0.0.1:${silent.localPort}/healthz").build()

            val result = exchange(quickRead.pinned(certificate.pin()).firstTry, request, 2.seconds) { response ->
                readGateResponse(response) { _, _ -> Unit }
            }

            val failure = result.transport()
            assertEquals(TransportFailure.TIMEOUT, failure.kind)
            assertFalse("the handshake never completed", failure.connected)
        }
    }

    @Test
    fun `a response that stalls after the request was sent is a failure after connecting`() = runTest {
        gate.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        val failure = healthAt(gate.endpoint()).transport()

        assertEquals(TransportFailure.TIMEOUT, failure.kind)
        assertTrue(failure.connected)
        assertFalse(isConnectFailure(failure))
    }

    @Test
    fun `marking the connection leaves the request and the answer alone`() = runTest {
        gate.enqueue(json(200, "{}"))

        assertEquals(Attempt.Success(Unit), healthAt(gate.endpoint()))
        assertEquals("/healthz", gate.takeRequest().url.encodedPath)
    }
}
