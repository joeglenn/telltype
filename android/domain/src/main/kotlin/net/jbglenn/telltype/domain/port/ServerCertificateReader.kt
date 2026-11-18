package net.jbglenn.telltype.domain.port

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerEndpoint

/**
 * Reads the certificate a server presents before it is trusted, so setup can show its fingerprint for the user to
 * confirm once (docs/PLAN.md section 11, decision D6: the SSH model). The TLS handshake is aborted as soon as the
 * certificate has been read: no request and no key ever reach a server whose certificate is not confirmed.
 * Implemented by `:network`; called by the Setup screen (A6), which then stores the confirmed fingerprint in
 * `ServerConfig.certificate`. Added by A3.
 */
public interface ServerCertificateReader {
    /**
     * The SHA-256 fingerprint of the certificate [endpoint] presents. Bounded by the connect and handshake
     * timeouts; cancellable. Fails with the `NetworkError` that explains why no certificate could be read
     * (`ServerUnreachable`, `LocalNetworkPermissionMissing`).
     */
    public suspend fun presentedFingerprint(endpoint: ServerEndpoint): Outcome<CertificateFingerprint, NetworkError>
}
