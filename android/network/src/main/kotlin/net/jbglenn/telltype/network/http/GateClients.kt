package net.jbglenn.telltype.network.http

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.PrivateAddresses
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.network.tls.CapturingTrustManager
import net.jbglenn.telltype.network.tls.PinnedHostnameVerifier
import net.jbglenn.telltype.network.tls.PinnedTrustManager
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.TlsVersion

/**
 * The one OkHttp client of the app (one connection pool, one thread pool) and its variants per kind of address
 * (D60): plain HTTP, whose host names must resolve to private addresses only ([PrivateOnlyDns]); HTTPS with the
 * system's trust store and host-name check; HTTPS that trusts only a pinned certificate and checks no host name
 * (D25); and one that only reads a certificate for setup. TLS 1.2 or newer; no redirects, no cache, no automatic
 * retries (the router decides). Every call records whether it got a connection ([ConnectionProgress]).
 *
 * @param timeouts connect, read and write timeouts; whole-call timeouts are set per call.
 * @param eventListenerFactory observes calls; tests use it to see cancellation, production uses none.
 * @param dns resolves host names; tests may replace it.
 */
internal class GateClients(
    private val timeouts: NetworkTimeouts,
    eventListenerFactory: EventListener.Factory = EventListener.Factory { EventListener.NONE },
    dns: Dns = Dns.SYSTEM,
) {
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeouts.connect)
        .writeTimeout(timeouts.write.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.read.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .connectionSpecs(listOf(TLS_ONLY, ConnectionSpec.CLEARTEXT))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .dns(dns)
        .addNetworkInterceptor(ConnectionProgress.Marker)
        .eventListenerFactory(eventListenerFactory)
        .build()

    private val system: RouteClients = pair(base)
    private val plain: RouteClients = pair(base.newBuilder().dns(PrivateOnlyDns(dns)).build())

    private val lock = Any()
    private var pinned: Pair<CertificateFingerprint, RouteClients>? = null

    /**
     * The clients for [endpoint]: [pin] set means trust exactly that certificate; otherwise the system's trust for
     * HTTPS, or the private-only client for HTTP.
     */
    fun forEndpoint(endpoint: ServerEndpoint, pin: CertificateFingerprint?): RouteClients = when {
        endpoint.isPlainHttp -> plain
        pin != null -> pinned(pin)
        else -> system
    }

    /** The clients that trust exactly the certificate with fingerprint [pin]. Cached until the pin changes. */
    fun pinned(pin: CertificateFingerprint): RouteClients = synchronized(lock) {
        pinned?.takeIf { it.first == pin }?.second
            ?: pair(withTrust(PinnedTrustManager(pin)).hostnameVerifier(PinnedHostnameVerifier(pin)).build())
                .also { pinned = pin to it }
    }

    /**
     * A client whose every handshake fails after reading the server's certificate (setup only). It connects with
     * the patient timeout: setup asks one address the user just typed, so waiting matters less than reaching it.
     */
    fun capturing(): OkHttpClient = withTrust(CapturingTrustManager())
        .hostnameVerifier { _, _ -> false }
        .connectTimeout(timeouts.connectRetry)
        .build()

    private fun pair(client: OkHttpClient) =
        RouteClients(client, client.newBuilder().connectTimeout(timeouts.connectRetry).build())

    private fun withTrust(trustManager: X509TrustManager): OkHttpClient.Builder {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), null)
        return base.newBuilder().sslSocketFactory(context.socketFactory, trustManager)
    }

    private fun OkHttpClient.Builder.connectTimeout(timeout: Duration): OkHttpClient.Builder =
        connectTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    private companion object {
        val TLS_ONLY: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()
    }
}

/**
 * Plain HTTP goes only to private addresses (D60): a host name is used only with the private addresses it resolves
 * to, and one that resolves to none fails as unknown. IP literals are checked by the router before any call.
 */
internal class PrivateOnlyDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).filter { PrivateAddresses.isPrivateAddress(it.address) }.ifEmpty {
            throw UnknownHostException("$hostname has no private address; plain HTTP is refused")
        }
}

/**
 * The two clients of one kind of address. They share one connection pool, so a connection opened by either
 * serves both.
 *
 * @property firstTry connects with `NetworkTimeouts.connect`: the first try.
 * @property retry connects with `NetworkTimeouts.connectRetry`: the one retry.
 */
internal class RouteClients(val firstTry: OkHttpClient, val retry: OkHttpClient)
