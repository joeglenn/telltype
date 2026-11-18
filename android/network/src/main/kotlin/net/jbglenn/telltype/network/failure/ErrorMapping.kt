package net.jbglenn.telltype.network.failure

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.LocalNetworkPermissionMissing
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.network.tls.PinMismatchException

/**
 * The one `NetworkError` for each failure (contracts page 2, plan section 10, Appendix C). Pure and total.
 *
 * | Failure | Error |
 * |---|---|
 * | 401, 403 | `KeyRejected` |
 * | 503 (`not_ready`, with or without `Retry-After`) | `ServerWarmingUp` |
 * | 429, 502, 504, any other 5xx | `ServerFailed(status, code)` |
 * | 400, 413, 415, any other 4xx | `RequestRejected(status, code)` |
 * | 1xx, 2xx with a bad body, 3xx (redirects are never followed) | `MalformedResponse` |
 * | certificate other than the pinned one, or one the system does not trust | `CertificateMismatch` |
 * | EPERM from the socket (local network permission) | `LocalNetworkPermissionMissing` |
 * | timeout, no route, refused, connection lost, other TLS failure | `ServerUnreachable` |
 */
internal fun networkErrorFor(failure: CallFailure): NetworkError = when (failure) {
    is CallFailure.Http -> httpError(failure)

    CallFailure.MalformedBody -> MalformedResponse

    is CallFailure.Transport -> when (failure.kind) {
        TransportFailure.CERTIFICATE_MISMATCH, TransportFailure.UNTRUSTED_CERTIFICATE -> CertificateMismatch

        TransportFailure.LOCAL_NETWORK_DENIED -> LocalNetworkPermissionMissing

        TransportFailure.TIMEOUT,
        TransportFailure.NO_ROUTE,
        TransportFailure.REFUSED,
        TransportFailure.CONNECTION_LOST,
        TransportFailure.TLS,
        -> ServerUnreachable
    }
}

private fun httpError(failure: CallFailure.Http): NetworkError = when (val status = failure.status) {
    Status.UNAUTHORIZED, Status.FORBIDDEN -> KeyRejected
    Status.SERVICE_UNAVAILABLE -> ServerWarmingUp
    Status.TOO_MANY_REQUESTS -> ServerFailed(status, failure.serverCode)
    in Status.CLIENT_ERROR -> RequestRejected(status, failure.serverCode)
    in Status.SERVER_ERROR -> ServerFailed(status, failure.serverCode)
    else -> MalformedResponse
}

/** Where [failure]'s cause lies (see [FailureScope]). */
internal fun scopeOf(failure: CallFailure): FailureScope = when (failure) {
    is CallFailure.Transport -> FailureScope.ADDRESS

    is CallFailure.Http -> when (failure.status) {
        Status.BAD_GATEWAY, Status.SERVICE_UNAVAILABLE, Status.GATEWAY_TIMEOUT -> FailureScope.SERVER
        else -> FailureScope.REQUEST
    }

    CallFailure.MalformedBody -> FailureScope.REQUEST
}

/**
 * Whether trying the same address once more can help: transient transport failures and 502/503/504. A
 * certificate mismatch or a refused permission gives the same answer every time; 4xx are never retried.
 */
internal fun isTransient(failure: CallFailure): Boolean = when (failure) {
    is CallFailure.Transport ->
        failure.kind !in setOf(
            TransportFailure.CERTIFICATE_MISMATCH,
            TransportFailure.UNTRUSTED_CERTIFICATE,
            TransportFailure.LOCAL_NETWORK_DENIED,
        )

    is CallFailure.Http -> scopeOf(failure) == FailureScope.SERVER

    CallFailure.MalformedBody -> false
}

/**
 * Whether [failure] happened before the call had a connection: the address did not answer at all. Its retry
 * waits until every other address has been tried, because a second try at a silent address is the slowest way
 * to reach the server.
 */
internal fun isConnectFailure(failure: CallFailure): Boolean = failure is CallFailure.Transport && !failure.connected

/** Whether [status] is a success the body parser should read. */
internal fun isSuccess(status: Int): Boolean = status in Status.SUCCESS

/**
 * Classifies the exception OkHttp reported for a call without an HTTP answer. Pure: looks only at the exception
 * types, messages and causes. The local network permission (Android 17) is recognised by the `EPERM` the socket
 * reports, as far as Android exposes it.
 */
internal fun classifyTransport(error: Throwable): TransportFailure {
    val chain = generateSequence(error) { it.cause }.take(Status.MAX_CAUSE_DEPTH).toList()
    return when {
        chain.any { it is PinMismatchException } -> TransportFailure.CERTIFICATE_MISMATCH

        chain.any(::isUntrusted) -> TransportFailure.UNTRUSTED_CERTIFICATE

        chain.any { it.message.orEmpty().let { m -> "EPERM" in m || "Operation not permitted" in m } } ->
            TransportFailure.LOCAL_NETWORK_DENIED

        chain.any { it is SocketTimeoutException } -> TransportFailure.TIMEOUT

        error is InterruptedIOException && error.message == "timeout" -> TransportFailure.TIMEOUT

        chain.any { it is UnknownHostException || it is NoRouteToHostException } -> TransportFailure.NO_ROUTE

        chain.any { it is ConnectException } -> TransportFailure.REFUSED

        chain.any { it is SSLException } -> TransportFailure.TLS

        else -> TransportFailure.CONNECTION_LOST
    }
}

/**
 * The system's trust store rejected the certificate (self-signed or unknown issuer: a certification path
 * failure), or the certificate does not name the host. Either is offered for confirmation in Setup (D60, D25).
 */
private fun isUntrusted(error: Throwable): Boolean =
    error is CertPathValidatorException || error is CertPathBuilderException || error is SSLPeerUnverifiedException

/** HTTP statuses and status classes the mapping distinguishes. */
private object Status {
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val TOO_MANY_REQUESTS = 429
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504
    val SUCCESS = 200..299
    val CLIENT_ERROR = 400..499
    val SERVER_ERROR = 500..599
    const val MAX_CAUSE_DEPTH = 8
}
