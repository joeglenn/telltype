package net.jbglenn.telltype.network.setup

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.network.ShortTimeouts
import net.jbglenn.telltype.network.endpoint
import net.jbglenn.telltype.network.http.GateClients
import net.jbglenn.telltype.network.json
import net.jbglenn.telltype.network.pin
import net.jbglenn.telltype.network.refusingEndpoint
import net.jbglenn.telltype.network.selfSignedCertificate
import net.jbglenn.telltype.network.tlsServer
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Test

class TlsServerCertificateReaderTest {
    private val reader = TlsServerCertificateReader(GateClients(ShortTimeouts), ShortTimeouts, RecordingLogger())

    @Test
    fun `the presented certificate is read and no request is sent`() = runTest {
        val certificate = selfSignedCertificate()
        tlsServer(certificate).use { gate ->
            gate.enqueue(json(200, "{}"))

            assertEquals(Outcome.Ok(certificate.pin()), reader.presentedFingerprint(gate.endpoint()))
            assertEquals("the handshake was aborted before any request", 0, gate.requestCount)
        }
    }

    @Test
    fun `nothing listening is ServerUnreachable`() = runTest {
        assertEquals(Outcome.Err(ServerUnreachable), reader.presentedFingerprint(refusingEndpoint()))
    }
}
