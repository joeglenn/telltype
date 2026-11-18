package net.jbglenn.telltype.network.tls

import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSession
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.network.selfSignedCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PinningTest {
    private val gate = selfSignedCertificate().certificate
    private val other = selfSignedCertificate("impostor").certificate

    @Test
    fun `the fingerprint is the lower-case SHA-256 of the DER encoding`() {
        val expected = MessageDigest.getInstance("SHA-256").digest(gate.encoded).joinToString("") { "%02x".format(it) }

        assertEquals(CertificateFingerprint(expected), gate.fingerprint())
    }

    @Test
    fun `the pinned trust manager accepts only the pinned certificate`() {
        val manager = PinnedTrustManager(gate.fingerprint())

        manager.checkServerTrusted(arrayOf(gate), "ECDHE_ECDSA")
        val mismatch = assertThrows(PinMismatchException::class.java) {
            manager.checkServerTrusted(arrayOf(other), "ECDHE_ECDSA")
        }
        assertEquals(other.fingerprint(), mismatch.presented)
        assertThrows(CertificateException::class.java) { manager.checkServerTrusted(emptyArray(), "RSA") }
        assertThrows(CertificateException::class.java) { manager.checkClientTrusted(arrayOf(gate), "RSA") }
        assertEquals(0, manager.acceptedIssuers.size)
    }

    @Test
    fun `the hostname verifier accepts any name for the pinned certificate only`() {
        val verifier = PinnedHostnameVerifier(gate.fingerprint())

        assertTrue(verifier.verify("192.0.2.10", sessionWith(gate)))
        assertTrue(verifier.verify("server.example", sessionWith(gate)))
        assertFalse(verifier.verify("192.0.2.10", sessionWith(other)))
        assertFalse(verifier.verify("192.0.2.10", null))
    }

    @Test
    fun `the capturing trust manager always rejects, carrying the fingerprint`() {
        val captured = assertThrows(CertificateCapturedException::class.java) {
            CapturingTrustManager().checkServerTrusted(arrayOf(gate), "ECDHE_ECDSA")
        }
        assertEquals(gate.fingerprint(), captured.presented)
    }

    /** An [SSLSession] that only knows its peer certificate. */
    private fun sessionWith(certificate: X509Certificate): SSLSession = Proxy.newProxyInstance(
        SSLSession::class.java.classLoader,
        arrayOf(SSLSession::class.java),
    ) { _, method, _ ->
        if (method.name ==
            "getPeerCertificates"
        ) {
            arrayOf<Certificate>(certificate)
        } else {
            throw UnsupportedOperationException()
        }
    } as SSLSession
}
