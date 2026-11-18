package net.jbglenn.telltype.testing

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.domain.port.ServerCertificateReader

/** Certificate reader fake. By default every server presents [DefaultFingerprint]. */
public class FakeServerCertificateReader : ServerCertificateReader {
    /** [presentedFingerprint]: the argument is the endpoint. */
    public val readCall: ScriptedCall<ServerEndpoint, Outcome<CertificateFingerprint, NetworkError>> =
        ScriptedCall { Outcome.Ok(DefaultFingerprint) }

    override suspend fun presentedFingerprint(endpoint: ServerEndpoint): Outcome<CertificateFingerprint, NetworkError> =
        readCall.answer(endpoint)

    public companion object {
        /** The fingerprint every server presents unless scripted otherwise (64 times `a`). */
        public val DefaultFingerprint: CertificateFingerprint = CertificateFingerprint("a".repeat(64))
    }
}
