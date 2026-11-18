package net.jbglenn.telltype.network.http

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Whether one call got a usable connection (TCP connected and TLS handshake done) before it failed. A failure
 * before that point means the address itself did not answer, so the router moves on to the next address at once;
 * a failure after it is retried on the same address. Carried as a request tag and set by [Marker].
 */
internal class ConnectionProgress {
    /** True once OkHttp handed the call an established connection. */
    @Volatile
    var connected: Boolean = false
        private set

    /**
     * A network interceptor: OkHttp runs network interceptors only once the call has its connection (fresh or
     * pooled), so reaching it is the proof. Changes nothing about the request.
     */
    object Marker : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            chain.request().tag(ConnectionProgress::class.java)?.connected = true
            return chain.proceed(chain.request())
        }
    }
}
