package net.jbglenn.telltype.core.model.settings

/**
 * One address Setup tests before saving it (D60): with the typed key and, for a self-signed certificate, the
 * fingerprint the user confirmed.
 *
 * @property endpoint the address.
 * @property key the typed or saved key, null for none; never printed.
 * @property certificate the pinned certificate, null to use the system's trust (or for HTTP).
 * @property roles what the address must serve.
 */
public data class ServerCandidate(
    public val endpoint: ServerEndpoint,
    public val key: ServerKey?,
    public val certificate: CertificateFingerprint?,
    public val roles: Set<ServerRole>,
)

/** Which request proved that an address serves a role. */
public enum class ServerCheckMethod(public val id: String) {
    /** `GET /v1/models` answered with a model list. */
    MODELS("models"),

    /** `GET /healthz` answered in our gate's format. */
    GATE_HEALTH("gate_health"),

    /** A one-second silent WAV was transcribed (a speech server without `/v1/models`). */
    SILENT_TRANSCRIPTION("silent_transcription"),
}

/**
 * What testing one [ServerCandidate] found.
 *
 * @property endpoint the address.
 * @property methods the check that passed for each role asked.
 * @property models the models it lists; null when it lists none.
 * @property health our gate's health body, null for other servers.
 */
public data class ServerCheck(
    public val endpoint: ServerEndpoint,
    public val methods: Map<ServerRole, ServerCheckMethod>,
    public val models: List<OfferedModel>?,
    public val health: ServerHealth?,
)
