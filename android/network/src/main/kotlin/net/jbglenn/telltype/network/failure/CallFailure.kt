package net.jbglenn.telltype.network.failure

import kotlin.time.Duration

/**
 * How one attempt at one address failed, before it becomes a `NetworkError` ([networkErrorFor]). Keeps the
 * detail the router needs to decide on retry and failover.
 */
internal sealed interface CallFailure {
    /**
     * The server answered with a non-2xx status.
     *
     * @property status the HTTP status.
     * @property serverCode `error.code` of the body (Appendix C) or `model_not_found`, null when neither.
     * @property retryAfter the `Retry-After` header in seconds, null when absent or not a number of seconds.
     */
    data class Http(val status: Int, val serverCode: String?, val retryAfter: Duration?) : CallFailure

    /**
     * No HTTP answer: the connection, the handshake or the exchange failed.
     *
     * @property kind what went wrong.
     * @property connected the call had an established connection (TCP and TLS) when it failed. False means the
     *   address itself did not answer (refused, no route, connect timeout, handshake never completed), so the
     *   router moves on to the next address at once ([isConnectFailure]).
     */
    data class Transport(val kind: TransportFailure, val connected: Boolean) : CallFailure

    /** A 2xx answer whose body is not the documented shape (or is cut off, or too large). */
    data object MalformedBody : CallFailure
}

/** Why a call produced no HTTP answer. */
internal enum class TransportFailure {
    /** A connect, read, write or whole-call timeout. */
    TIMEOUT,

    /** The host name did not resolve or there is no route to the address. */
    NO_ROUTE,

    /** Nothing listens at the address (connection refused). */
    REFUSED,

    /** The connection broke or closed mid-exchange. */
    CONNECTION_LOST,

    /** The TLS handshake failed for a reason other than the certificate. */
    TLS,

    /** The server presented a certificate other than the pinned one. */
    CERTIFICATE_MISMATCH,

    /** No certificate is pinned and the system's trust store does not trust the one presented. */
    UNTRUSTED_CERTIFICATE,

    /** Android refused the connection (EPERM): the local network permission is missing (Android 17). */
    LOCAL_NETWORK_DENIED,
}

/** Where the cause of a failure lies, which decides retry and failover (docs/PLAN.md section 10). */
internal enum class FailureScope {
    /** The address could not be used: retry it once when safe, then fail over to the next address. */
    ADDRESS,

    /** The gate was reached but a backend failed (502, 503, 504): retry once when safe, no failover. */
    SERVER,

    /** The gate refused this request (4xx) or answered nonsense: final, never retried. */
    REQUEST,
}
