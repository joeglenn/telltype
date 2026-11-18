package net.jbglenn.telltype.domain.port

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerRole

/**
 * Tests an address before Setup saves it (D60): `GET /v1/models`; when the address has none, our gate's
 * `/healthz` or, for speech, a one-second silent transcription. With no pinned certificate an HTTPS address must be
 * trusted by the system: a certificate it does not trust fails with `CertificateMismatch`, after which Setup reads
 * the fingerprint ([ServerCertificateReader]) and asks the user. Implemented by `:network` (G1).
 */
public interface ServerConnectionChecker {
    /** Checks [candidate] for each of its roles. Bounded by the network timeouts; cancellable. */
    public suspend fun check(candidate: ServerCandidate): Outcome<ServerCheck, NetworkError>
}

/**
 * The models the stored server offers, for Settings' model rows (D58, D60). Implemented by `:network` (G1).
 */
public interface ServerModelCatalog {
    /**
     * `GET /v1/models` at the address that serves [role]. Our gate, which has no such route, offers its aliases
     * `cleanup` and `assistant` for text. An address that lists nothing gives an empty list.
     */
    public suspend fun list(role: ServerRole): Outcome<List<OfferedModel>, NetworkError>
}
