package net.jbglenn.telltype.network.setup

import javax.inject.Inject
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.domain.port.ServerCertificateReader
import net.jbglenn.telltype.network.failure.CallFailure
import net.jbglenn.telltype.network.failure.classifyTransport
import net.jbglenn.telltype.network.failure.networkErrorFor
import net.jbglenn.telltype.network.http.GateClients
import net.jbglenn.telltype.network.http.GateRoute
import net.jbglenn.telltype.network.http.NetworkTimeouts
import net.jbglenn.telltype.network.http.Sent
import net.jbglenn.telltype.network.http.send
import net.jbglenn.telltype.network.tls.CapturingTrustManager
import net.jbglenn.telltype.network.tls.CertificateCapturedException
import okhttp3.Request

/**
 * Reads the certificate an HTTPS server presents (Setup, after the system's trust refused it) with a client whose
 * trust manager records it and then fails the
 * handshake on purpose ([CapturingTrustManager]), so not even the request line, let alone the key, is sent. Any
 * other failure is mapped like every other call.
 */
internal class TlsServerCertificateReader @Inject constructor(
    private val clients: GateClients,
    private val timeouts: NetworkTimeouts,
    private val logger: Logger,
) : ServerCertificateReader {

    override suspend fun presentedFingerprint(endpoint: ServerEndpoint): Outcome<CertificateFingerprint, NetworkError> {
        val request = Request.Builder().url(endpoint.baseUrl + GateRoute.HEALTH.path).get().build()
        val error = when (val sent = send(clients.capturing(), request, timeouts.certificateRead) { Unit }) {
            is Sent.Answered -> MalformedResponse

            is Sent.Failed -> {
                val captured = generateSequence<Throwable>(sent.error) { it.cause }
                    .filterIsInstance<CertificateCapturedException>()
                    .firstOrNull()
                if (captured != null) {
                    logger.info(TAG) { "read the certificate of ${endpoint.baseUrl}" }
                    return Outcome.Ok(captured.presented)
                }
                networkErrorFor(CallFailure.Transport(classifyTransport(sent.error), sent.connected))
            }
        }
        logger.info(TAG) { "could not read the certificate of ${endpoint.baseUrl}: ${error.code}" }
        return Outcome.Err(error)
    }

    private companion object {
        val TAG = LogTag("CertificateReader")
    }
}
