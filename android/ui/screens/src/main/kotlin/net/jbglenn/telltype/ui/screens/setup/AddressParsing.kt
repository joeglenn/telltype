package net.jbglenn.telltype.ui.screens.setup

import androidx.annotation.StringRes
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.ui.screens.R

/**
 * Reads an address as people type it (D60): `192.0.2.10`, `192.0.2.10:8170`, `https://server.example`,
 * `http://192.0.2.10:8000/v1`, `[fd00::1]:8170` or a bare IPv6 address. With `http://` or `https://` a missing port
 * is the scheme's own (80, 443), as in a URL; without a scheme the address is HTTPS on our gate's 8170. A trailing
 * `/` or `/v1` (how OpenAI-compatible servers print their base URL) is dropped; any other path is refused. Returns
 * null for anything that is not a host with an optional port. Does not judge whether HTTP is allowed: [checkAddress].
 */
fun parseEndpoint(text: String): ServerEndpoint? {
    val (scheme, rest) = splitScheme(text.trim())
    val hostPort = stripPath(rest) ?: return null
    return splitHostPort(hostPort)?.let { (host, portText) ->
        endpointOrNull(host, portText, scheme ?: Scheme.HTTPS, defaultPort(scheme))
    }
}

/**
 * Checks one address and says what is wrong with it: empty, spaces, a port outside 1 to 65535, not a host, or
 * `http://` to an address that is not private (D60: plain HTTP only inside the user's own network).
 */
fun checkAddress(text: String): AddressCheck {
    val trimmed = text.trim()
    val endpoint = parseEndpoint(trimmed)
    return when {
        trimmed.isEmpty() -> AddressCheck.Problem(R.string.setup_error_no_address)

        trimmed.any { it.isWhitespace() } -> AddressCheck.Problem(R.string.setup_error_address_spaces)

        endpoint == null -> AddressCheck.Problem(
            if (hasBadPort(trimmed)) R.string.setup_error_address_port else R.string.setup_error_address,
        )

        endpoint.isPlainHttp && !endpoint.isPrivate -> AddressCheck.Problem(R.string.setup_error_address_http)

        else -> AddressCheck.Valid(endpoint)
    }
}

/** The result of [checkAddress]. */
sealed interface AddressCheck {
    /**
     * A usable address.
     *
     * @property endpoint the address with its scheme and port.
     */
    data class Valid(val endpoint: ServerEndpoint) : AddressCheck

    /**
     * What to fix.
     *
     * @property message the message shown under the field.
     */
    data class Problem(@param:StringRes val message: Int) : AddressCheck
}

/**
 * The address as the user would type it back: `host:port` for HTTPS (IPv6 in brackets), `http://host:port` for
 * plain HTTP, so it parses back to the same address.
 */
fun ServerEndpoint.displayText(): String {
    val hostPort = if (':' in host) "[$host]:$port" else "$host:$port"
    return if (isPlainHttp) "${Scheme.HTTP.id}://$hostPort" else hostPort
}

private fun hasBadPort(text: String): Boolean {
    val portText = stripPath(splitScheme(text).second)?.let(::splitHostPort)?.second?.takeIf { it.isNotEmpty() }
    val port = portText?.toIntOrNull()
    return portText != null && (port == null || port !in 1..AddressDefaults.MAX_PORT)
}

private fun splitScheme(text: String): Pair<Scheme?, String> {
    val scheme = Scheme.entries.firstOrNull { text.startsWith(it.id + "://", ignoreCase = true) }
    return scheme to if (scheme == null) text else text.substring(scheme.id.length + SEPARATOR.length)
}

private fun defaultPort(scheme: Scheme?): Int = scheme?.defaultPort ?: AddressDefaults.DEFAULT_GATE_PORT

/** The host and port part without a trailing `/` or `/v1`; null when another path follows. */
private fun stripPath(text: String): String? {
    val withoutSlash = text.removeSuffix("/")
    val withoutApi = withoutSlash.removeSuffix(AddressDefaults.API_PATH).removeSuffix("/")
    return withoutApi.takeUnless { '/' in it }
}

private fun splitHostPort(text: String): Pair<String, String>? = when {
    text.startsWith("[") -> BracketedV6.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }
    text.count { it == ':' } > 1 -> text to ""
    else -> text.substringBefore(':') to text.substringAfter(':', missingDelimiterValue = "")
}

private fun endpointOrNull(host: String, portText: String, scheme: Scheme, defaultPort: Int): ServerEndpoint? {
    val port = if (portText.isEmpty()) defaultPort else portText.toIntOrNull()
    val validHost = if (':' in host) Ipv6.matches(host) else HostName.matches(host)
    return if (validHost && port != null && port in 1..AddressDefaults.MAX_PORT) {
        ServerEndpoint(host, port, scheme)
    } else {
        null
    }
}

private const val SEPARATOR = "://"
private val BracketedV6 = Regex("\\[([0-9a-fA-F:.]+)](?::(\\d+))?")
private val HostName = Regex("[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*")
private val Ipv6 = Regex("[0-9a-fA-F.]*:[0-9a-fA-F:.]*")

/** Fixed values of address parsing. */
internal object AddressDefaults {
    /** Our gate's port (docs/PLAN.md section 12.2), used when an address has neither scheme nor port. */
    const val DEFAULT_GATE_PORT: Int = 8170
    const val MAX_PORT = 65_535

    /** The path OpenAI-compatible servers put after their base URL. */
    const val API_PATH = "/v1"
}
