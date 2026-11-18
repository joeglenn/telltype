package net.jbglenn.telltype.network.http

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every timeout of the gate client (docs/PLAN.md section 9: every external wait has a timeout). The whole-call
 * timeouts bound one attempt at one address from connecting to reading the last body byte; retries and failover
 * add attempts, each with its own bound.
 *
 * **Why the first connect is short.** Every address is a private one (home Wi-Fi, WireGuard, Tailscale), and the
 * usual reason one does not answer is that the phone is on another network, where packets to it vanish: the
 * connect timeout is then the whole price of the dead address. A TCP connect costs one round trip, plus one for
 * WireGuard's handshake when the tunnel was idle. Home Wi-Fi answers in a few milliseconds; WireGuard or Tailscale
 * over a mobile link typically take 50 to 300 ms per round trip and around 600 ms on a poor link, so 1.5 s covers
 * tunnel handshake plus SYN even then, or one lost SYN (resent after 1 s) on a fast link.
 *
 * The trade-off: a link slower than that (a congested or 2G-class mobile link through the tunnel) fails its first
 * connect. It is not lost: once every address has had its first try, its one retry connects with [connectRetry],
 * the patient value used before. Such a link pays [connect] once more plus the retry pause; a dead address costs
 * [connect] instead of twice the old 5 s.
 *
 * @property connect TCP connect on the first try at an address. OkHttp bounds the TLS handshake by [read], and
 *   every attempt by its whole-call timeout.
 * @property connectRetry TCP connect on the one retry at an address, and when reading a certificate in setup.
 * @property write one write to the socket while uploading (not the whole upload).
 * @property read the longest silence while waiting for bytes; the gate may work up to 160 s on a long recording
 *   before it answers, so this is long and the whole-call timeout of each route is the real bound.
 * @property health whole call for `GET /healthz`, probes and warm-up.
 * @property transcription whole call for one upload and transcription; above the gate's 160 s speech timeout.
 * @property cleanup whole call for one cleanup request; the caller's model deadline is usually shorter.
 * @property command whole call for one Command Mode request; above the gate's 120 s assistant timeout.
 * @property certificateRead whole call for reading the certificate a server presents during setup.
 */
internal data class NetworkTimeouts(
    val connect: Duration,
    val connectRetry: Duration,
    val write: Duration,
    val read: Duration,
    val health: Duration,
    val transcription: Duration,
    val cleanup: Duration,
    val command: Duration,
    val certificateRead: Duration,
) {
    companion object {
        /** The values the app uses. */
        val Default: NetworkTimeouts = NetworkTimeouts(
            connect = 1_500.milliseconds,
            connectRetry = 5.seconds,
            write = 30.seconds,
            read = 170.seconds,
            health = 4.seconds,
            transcription = 180.seconds,
            cleanup = 60.seconds,
            command = 130.seconds,
            certificateRead = 6.seconds,
        )
    }
}
