package net.jbglenn.telltype.testing

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeServerCertificateReaderTest {
    private val endpoint = ServerEndpoint("192.0.2.10", 8170)

    @Test
    fun `certificate reader answers the default fingerprint and records the endpoint`() = runTest {
        val reader = FakeServerCertificateReader()

        assertEquals(
            Outcome.Ok(FakeServerCertificateReader.DefaultFingerprint),
            reader.presentedFingerprint(endpoint),
        )
        assertEquals(listOf(endpoint), reader.readCall.calls)
    }

    @Test
    fun `certificate reader can be scripted to fail`() = runTest {
        val reader = FakeServerCertificateReader()
        reader.readCall.returns(Outcome.Err(ServerUnreachable))

        assertEquals(Outcome.Err(ServerUnreachable), reader.presentedFingerprint(endpoint))
    }
}
