package net.jbglenn.telltype.network.failure

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.LocalNetworkPermissionMissing
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.network.tls.PinMismatchException
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMappingTest {
    private fun http(status: Int, code: String? = null) = CallFailure.Http(status, code, null)

    private fun transport(kind: TransportFailure) = CallFailure.Transport(kind, connected = false)

    /** Failure, expected error, expected scope, expected "retry on the same address can help". */
    private val table: List<Row> = listOf(
        Row(http(401, "unauthorized"), KeyRejected, FailureScope.REQUEST, transient = false),
        Row(CallFailure.Http(503, "not_ready", 5.seconds), ServerWarmingUp, FailureScope.SERVER, transient = true),
        Row(http(503), ServerWarmingUp, FailureScope.SERVER, transient = true),
        Row(http(429, "busy"), ServerFailed(429, "busy"), FailureScope.REQUEST, transient = false),
        Row(http(502, "backend_error"), ServerFailed(502, "backend_error"), FailureScope.SERVER, transient = true),
        Row(http(504, "backend_timeout"), ServerFailed(504, "backend_timeout"), FailureScope.SERVER, transient = true),
        Row(http(500), ServerFailed(500, null), FailureScope.REQUEST, transient = false),
        Row(http(400, "bad_request"), RequestRejected(400, "bad_request"), FailureScope.REQUEST, transient = false),
        Row(http(413, "too_large"), RequestRejected(413, "too_large"), FailureScope.REQUEST, transient = false),
        Row(
            http(415, "unsupported_audio"),
            RequestRejected(415, "unsupported_audio"),
            FailureScope.REQUEST,
            transient = false,
        ),
        Row(http(404, "not_found"), RequestRejected(404, "not_found"), FailureScope.REQUEST, transient = false),
        Row(http(301), MalformedResponse, FailureScope.REQUEST, transient = false),
        Row(http(101), MalformedResponse, FailureScope.REQUEST, transient = false),
        Row(CallFailure.MalformedBody, MalformedResponse, FailureScope.REQUEST, transient = false),
        Row(transport(TransportFailure.TIMEOUT), ServerUnreachable, FailureScope.ADDRESS, transient = true),
        Row(transport(TransportFailure.NO_ROUTE), ServerUnreachable, FailureScope.ADDRESS, transient = true),
        Row(transport(TransportFailure.REFUSED), ServerUnreachable, FailureScope.ADDRESS, transient = true),
        Row(transport(TransportFailure.CONNECTION_LOST), ServerUnreachable, FailureScope.ADDRESS, transient = true),
        Row(transport(TransportFailure.TLS), ServerUnreachable, FailureScope.ADDRESS, transient = true),
        Row(
            transport(TransportFailure.CERTIFICATE_MISMATCH),
            CertificateMismatch,
            FailureScope.ADDRESS,
            transient = false,
        ),
        Row(
            transport(TransportFailure.UNTRUSTED_CERTIFICATE),
            CertificateMismatch,
            FailureScope.ADDRESS,
            transient = false,
        ),
        Row(
            transport(TransportFailure.LOCAL_NETWORK_DENIED),
            LocalNetworkPermissionMissing,
            FailureScope.ADDRESS,
            transient = false,
        ),
    )

    private data class Row(
        val failure: CallFailure,
        val error: NetworkError,
        val scope: FailureScope,
        val transient: Boolean,
    )

    @Test
    fun `every failure maps to exactly one error, scope and retry rule`() {
        for (row in table) {
            assertEquals("error for ${row.failure}", row.error, networkErrorFor(row.failure))
            assertEquals("scope for ${row.failure}", row.scope, scopeOf(row.failure))
            assertEquals("transient for ${row.failure}", row.transient, isTransient(row.failure))
        }
    }

    @Test
    fun `every transport kind is covered by the table`() {
        val covered = table.mapNotNull { (it.failure as? CallFailure.Transport)?.kind }.toSet()
        assertEquals(TransportFailure.entries.toSet(), covered)
    }

    @Test
    fun `only a transport failure before connecting is a connect failure`() {
        for (kind in TransportFailure.entries) {
            assertEquals("$kind before", true, isConnectFailure(CallFailure.Transport(kind, connected = false)))
            assertEquals("$kind after", false, isConnectFailure(CallFailure.Transport(kind, connected = true)))
        }
        assertEquals(false, isConnectFailure(http(503)))
        assertEquals(false, isConnectFailure(CallFailure.MalformedBody))
    }

    @Test
    fun `transport exceptions are classified`() {
        val pin = CertificateFingerprint("b".repeat(64))
        val rows: List<Pair<Throwable, TransportFailure>> = listOf(
            SocketTimeoutException("connect timed out") to TransportFailure.TIMEOUT,
            InterruptedIOException("timeout") to TransportFailure.TIMEOUT,
            UnknownHostException("server.local") to TransportFailure.NO_ROUTE,
            NoRouteToHostException("No route to host") to TransportFailure.NO_ROUTE,
            ConnectException("Failed to connect to /127.0.0.1:1") to TransportFailure.REFUSED,
            ConnectException(
                "failed to connect to /192.0.2.10 (port 8170): connect failed: EPERM (Operation not permitted)",
            ) to TransportFailure.LOCAL_NETWORK_DENIED,
            SocketException("socket failed: EPERM (Operation not permitted)") to TransportFailure.LOCAL_NETWORK_DENIED,
            SSLHandshakeException("handshake failed").apply { initCause(PinMismatchException(pin)) } to
                TransportFailure.CERTIFICATE_MISMATCH,
            SSLHandshakeException("no cipher suites in common") to TransportFailure.TLS,
            SSLHandshakeException("trust").apply {
                initCause(
                    java.security.cert.CertPathValidatorException("Trust anchor for certification path not found."),
                )
            } to TransportFailure.UNTRUSTED_CERTIFICATE,
            javax.net.ssl.SSLPeerUnverifiedException("Hostname 192.0.2.10 not verified") to
                TransportFailure.UNTRUSTED_CERTIFICATE,
            IOException("unexpected end of stream") to TransportFailure.CONNECTION_LOST,
            SocketException("Connection reset") to TransportFailure.CONNECTION_LOST,
        )
        for ((error, expected) in rows) {
            assertEquals("classification of $error", expected, classifyTransport(error))
        }
    }
}
