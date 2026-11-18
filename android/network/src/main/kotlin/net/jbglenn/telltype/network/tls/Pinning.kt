package net.jbglenn.telltype.network.tls

import android.annotation.SuppressLint
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint

/** The SHA-256 fingerprint of this certificate's DER encoding, as stored in `ServerConfig.certificate`. */
internal fun X509Certificate.fingerprint(): CertificateFingerprint {
    val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
    val hex = buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and Hex.BYTE_MASK
            append(Hex.DIGITS[value ushr Hex.NIBBLE_BITS])
            append(Hex.DIGITS[value and Hex.NIBBLE_MASK])
        }
    }
    return CertificateFingerprint(hex)
}

/** The server presented a certificate other than the pinned one. Carries what it presented (not a secret). */
internal class PinMismatchException(val presented: CertificateFingerprint) :
    CertificateException("the server presented a certificate that is not the pinned one")

/**
 * Trusts exactly one certificate: the self-signed gate certificate whose SHA-256 fingerprint the user confirmed
 * in setup (docs/PLAN.md section 11, decision D6, the SSH model). System and user CAs are never consulted, so a
 * different certificate fails the handshake with [PinMismatchException] and is reported as `CertificateMismatch`;
 * there is no fallback to system trust.
 *
 * Neither the host name nor the validity period is checked: the same server is reached under several addresses
 * (home Wi-Fi address assigned by DHCP, WireGuard, Tailscale), and its identity is its key, which the pin fixes.
 */
@SuppressLint("CustomX509TrustManager") // Pinning a self-signed certificate is the design (D6); see the KDoc.
internal class PinnedTrustManager(private val pin: CertificateFingerprint) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("this client does not accept client certificates")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("the server presented no certificate")
        val presented = leaf.fingerprint()
        if (presented != pin) throw PinMismatchException(presented)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Accepts a connection exactly when its peer certificate is the pinned one, whatever host name was used (see
 * [PinnedTrustManager] for why the name is not checked). A second check after the trust manager's.
 */
internal class PinnedHostnameVerifier(private val pin: CertificateFingerprint) : HostnameVerifier {
    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val leaf = try {
            session?.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        return leaf?.fingerprint() == pin
    }
}

/** Thrown by [CapturingTrustManager] to abort a handshake once the certificate has been read. */
internal class CertificateCapturedException(val presented: CertificateFingerprint) :
    CertificateException("certificate read for confirmation; handshake aborted on purpose")

/**
 * Reads the leaf certificate a server presents and then always fails the handshake, so nothing (no request, no key)
 * is ever sent to a server whose certificate the user has not confirmed yet. Used only by setup.
 */
@SuppressLint("CustomX509TrustManager") // Never trusts anything: it records the certificate and rejects it.
internal class CapturingTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("this client does not accept client certificates")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("the server presented no certificate")
        throw CertificateCapturedException(leaf.fingerprint())
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Lowercase hex encoding. */
private object Hex {
    const val DIGITS = "0123456789abcdef"
    const val BYTE_MASK = 0xff
    const val NIBBLE_MASK = 0x0f
    const val NIBBLE_BITS = 4
}
