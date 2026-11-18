package net.jbglenn.telltype.core.model.settings

import kotlin.time.Duration
import kotlin.time.Instant
import net.jbglenn.telltype.core.model.error.NetworkError

/** How the app talks to an address (D60). */
public enum class Scheme(public val id: String, public val defaultPort: Int) {
    /** TLS: the system's trust store, or a self-signed certificate confirmed once in Setup (pinned). */
    HTTPS("https", defaultPort = 443),

    /** Plain HTTP: only to private addresses ([PrivateAddresses]); the app refuses it for any other. */
    HTTP("http", defaultPort = 80),
}

/**
 * One server address: Telltype Server's gate or any OpenAI-compatible server (D60).
 *
 * @property host an IPv4 address, IPv6 address (without brackets) or host name.
 * @property port TCP port, 1 to 65535.
 * @property scheme HTTPS (the default, and what every address stored before D60 is) or HTTP.
 */
public data class ServerEndpoint(
    public val host: String,
    public val port: Int,
    public val scheme: Scheme = Scheme.HTTPS,
) {
    init {
        require(host.isNotBlank()) { "a server endpoint needs a host" }
        require(port in 1..MAX_PORT) { "a TCP port is 1 to $MAX_PORT, got $port" }
    }

    /** The base URL, for example `https://192.0.2.10:8170` or `http://[fd00::1]:8000`. */
    public val baseUrl: String get() = "${scheme.id}://${if (':' in host) "[$host]" else host}:$port"

    /** Plain HTTP, allowed only when [isPrivate]. */
    public val isPlainHttp: Boolean get() = scheme == Scheme.HTTP

    /** The host is a private address or a `.local` name ([PrivateAddresses.isPrivateHost]). */
    public val isPrivate: Boolean get() = PrivateAddresses.isPrivateHost(host)

    private companion object {
        const val MAX_PORT = 65_535
    }
}

/**
 * The optional bearer key (D60: stock servers often have none). A secret: `toString` never prints it, and it is
 * stored only encrypted with a Keystore key (docs/PLAN.md section 11).
 */
@JvmInline
public value class ServerKey(public val secret: String) {
    override fun toString(): String = "ServerKey(‹hidden›)"
}

/**
 * The SHA-256 fingerprint of a self-signed certificate, confirmed by the user on first connect (the SSH model, D25).
 * After that, only a certificate with this fingerprint is accepted at that address; the host name is not checked.
 *
 * @property sha256Hex 64 lower-case hexadecimal digits, no separators.
 */
@JvmInline
public value class CertificateFingerprint(public val sha256Hex: String) {
    init {
        require(sha256Hex.length == HEX_LENGTH && sha256Hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "a SHA-256 fingerprint is $HEX_LENGTH lower-case hex digits"
        }
    }

    private companion object {
        const val HEX_LENGTH = 64
    }
}

/** What an address is used for (D60): speech to text, or the text model (cleanup and Command Mode). */
public enum class ServerRole {
    /** `POST /v1/audio/transcriptions`. */
    SPEECH,

    /** `POST /v1/chat/completions`. */
    TEXT,
}

/**
 * The model names the app sends (D58, D60), chosen in Setup and Settings from the server's `/v1/models`. Null
 * means "not chosen": speech then sends [DEFAULT_SPEECH], cleanup and commands the names of
 * `shared/prompts/request.json` (our gate's aliases `cleanup` and `assistant`).
 *
 * @property speech the speech model.
 * @property cleanup the cleanup model.
 * @property command the Command Mode model.
 */
public data class ServerModels(
    public val speech: String? = null,
    public val cleanup: String? = null,
    public val command: String? = null,
) {
    public companion object {
        /** Nothing chosen. */
        public val Unset: ServerModels = ServerModels()

        /** The speech model name many OpenAI-compatible servers accept (our gate ignores the field). */
        public const val DEFAULT_SPEECH: String = "whisper-1"
    }
}

/**
 * How to reach the server: Server group of Appendix G. One address for speech and text (D55), plus an optional
 * second address for the text model (D60); the app does not know or say which network path reaches either.
 *
 * @property address the server address (speech, and text unless [textAddress] is set); null until setup.
 * @property key the optional bearer key, sent to both addresses.
 * @property certificate the pinned certificate of [address]; null for HTTP or a certificate the system trusts.
 * @property textAddress the text model's address; null means [address].
 * @property textCertificate the pinned certificate of [textAddress], as [certificate].
 * @property models the chosen model names.
 */
public data class ServerConfig(
    public val address: ServerEndpoint?,
    public val key: ServerKey?,
    public val certificate: CertificateFingerprint?,
    public val textAddress: ServerEndpoint? = null,
    public val textCertificate: CertificateFingerprint? = null,
    public val models: ServerModels = ServerModels.Unset,
) {
    /** Setup is complete: an address was tested (key and certificate are optional since D60). */
    public val isConfigured: Boolean get() = address != null

    /** The address that serves [role]. */
    public fun endpointFor(role: ServerRole): ServerEndpoint? = when (role) {
        ServerRole.SPEECH -> address
        ServerRole.TEXT -> textAddress ?: address
    }

    /** The pinned certificate of the address that serves [role], null when the system's trust (or HTTP) applies. */
    public fun certificateFor(role: ServerRole): CertificateFingerprint? = when (role) {
        ServerRole.SPEECH -> certificate
        ServerRole.TEXT -> if (textAddress != null) textCertificate else certificate
    }

    public companion object {
        /** A fresh install. */
        public val Unconfigured: ServerConfig = ServerConfig(address = null, key = null, certificate = null)
    }
}

/**
 * The overall `status` of `GET /healthz` (Appendix C). When speech is not ready the gate answers 503 instead,
 * which arrives as a [NetworkError].
 *
 * @property id the value in the JSON body.
 */
public enum class ServerStatus(public val id: String) {
    /** Speech and cleanup are ready. */
    OK("ok"),

    /** Only speech is ready: dictation works with rule-based cleanup. */
    DEGRADED("degraded"),
}

/**
 * One backend's entry in the `/healthz` body.
 *
 * @property ready the backend has its model loaded.
 * @property model the model name, for example `parakeet-unified-en-0.6b-int8`.
 * @property engine the inference engine (`sherpa-onnx`), null when the body does not name one.
 */
public data class BackendHealth(public val ready: Boolean, public val model: String, public val engine: String?)

/**
 * The body of `GET /healthz` (Appendix C): only our gate has it; stock servers do not.
 *
 * @property status overall status.
 * @property version the gate's version, for example `0.1.0`.
 * @property stt the speech backend.
 * @property cleanup the cleanup model backend.
 * @property assistant the Command Mode model backend.
 */
public data class ServerHealth(
    public val status: ServerStatus,
    public val version: String,
    public val stt: BackendHealth,
    public val cleanup: BackendHealth,
    public val assistant: BackendHealth,
)

/**
 * The model names each address listed in `GET /v1/models` (null: the address lists none, or was not asked).
 *
 * @property speech what the speech address offers.
 * @property text what the text address offers.
 */
public data class OfferedModels(public val speech: List<OfferedModel>?, public val text: List<OfferedModel>?) {
    public companion object {
        /** Nothing known. */
        public val Unknown: OfferedModels = OfferedModels(speech = null, text = null)
    }
}

/**
 * One entry of `GET /v1/models`.
 *
 * @property id the name to send as `model`.
 * @property file the model file the server names for it (llama.cpp's `path`), null when it names none.
 * @property roles what the server says the model is for (Telltype Server's `telltype.roles`); null when the server
 *   does not say, and the app judges by the name.
 */
public data class OfferedModel(
    public val id: String,
    public val file: String? = null,
    public val roles: Set<ModelRole>? = null,
)

/** What a listed model can be used for, as Telltype Server's `telltype.roles` names it. */
public enum class ModelRole(public val wireName: String) {
    /** Transcription (`speech`). */
    SPEECH("speech"),

    /** Cleanup of dictated text (`cleanup`). */
    CLEANUP("cleanup"),

    /** Command Mode (`commands`). */
    COMMANDS("commands"),
    ;

    public companion object {
        /** The role of [name], or null for a name this build does not know. */
        public fun fromWire(name: String): ModelRole? = entries.firstOrNull { it.wireName == name }
    }
}

/** The latest known state of the connection to the server, for the "offline" mark and Diagnostics. */
public sealed interface ServerReachability {
    /** Not probed yet in this process. */
    public data object Unknown : ServerReachability

    /**
     * The last probe got an answer from every address.
     *
     * @property endpoint the server (speech) address.
     * @property health our gate's `/healthz` body, null for a server without one.
     * @property roundTrip time from sending the probe to reading the last answer.
     * @property checkedAt when the probe finished.
     * @property textEndpoint the separate text model address, null when the server address serves text too.
     * @property offered the models each address lists.
     */
    public data class Reachable(
        public val endpoint: ServerEndpoint,
        public val health: ServerHealth?,
        public val roundTrip: Duration,
        public val checkedAt: Instant,
        public val textEndpoint: ServerEndpoint? = null,
        public val offered: OfferedModels = OfferedModels.Unknown,
    ) : ServerReachability

    /**
     * The last probe failed.
     *
     * @property error why.
     * @property checkedAt when the probe finished.
     */
    public data class Unreachable(public val error: NetworkError, public val checkedAt: Instant) : ServerReachability
}
