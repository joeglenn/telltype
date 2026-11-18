package net.jbglenn.telltype.ui.designsystem.error

import androidx.annotation.StringRes
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.LocalNetworkPermissionMissing
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.ui.designsystem.R

/**
 * Why the server could not be used: the one classification behind every screen that talks about the server (B9).
 * Home's status card, the bubble's Problem state, History, Settings, Diagnostics and Setup all take their words from
 * here, so one failure is never "not answering" on one screen and "rejected the key" on another.
 *
 * @property message the short form (bubble, History, Home's card).
 * @property detail the long form with what to check (Settings, Diagnostics, Setup).
 */
enum class ServerCause(@param:StringRes val message: Int, @param:StringRes val detail: Int) {
    /** No address yet. */
    NOT_SET_UP(R.string.error_server_not_configured, R.string.error_server_not_configured),

    /** Android blocks the home-network address (local network access is off). */
    LOCAL_NETWORK_OFF(R.string.error_local_network_permission_missing, R.string.error_local_network_permission_missing),

    /** No answer at the address. */
    UNREACHABLE(R.string.error_server_unreachable, R.string.connection_unreachable),

    /** HTTP 401: the key is wrong or was changed on the server. */
    KEY_REJECTED(R.string.error_key_rejected, R.string.connection_key_rejected),

    /** The certificate is not the one confirmed in Setup. */
    CERTIFICATE_CHANGED(R.string.error_certificate_mismatch, R.string.connection_certificate_changed),

    /** The server does not offer the chosen model. */
    MODEL_MISSING(R.string.error_model_not_found, R.string.error_model_not_found),

    /** The server answers but its models are still loading. */
    WARMING_UP(R.string.error_server_warming_up, R.string.error_server_warming_up),

    /** The server answered with an error or an unreadable reply. */
    SERVER_ERROR(R.string.error_server_failed, R.string.connection_server_error),

    /** The server refused this request as such (a version mismatch or a recording it cannot use). */
    REQUEST_REFUSED(R.string.error_request_rejected, R.string.connection_rejected),
}

/** The cause of this network error. Exhaustive: a new network error does not compile until it has a cause. */
fun NetworkError.serverCause(): ServerCause = when (this) {
    ServerNotConfigured -> ServerCause.NOT_SET_UP

    LocalNetworkPermissionMissing -> ServerCause.LOCAL_NETWORK_OFF

    ServerUnreachable -> ServerCause.UNREACHABLE

    KeyRejected -> ServerCause.KEY_REJECTED

    CertificateMismatch -> ServerCause.CERTIFICATE_CHANGED

    ServerWarmingUp -> ServerCause.WARMING_UP

    is ServerFailed, MalformedResponse -> ServerCause.SERVER_ERROR

    is RequestRejected -> if (serverCode ==
        ServerCodes.MODEL_NOT_FOUND
    ) {
        ServerCause.MODEL_MISSING
    } else {
        ServerCause.REQUEST_REFUSED
    }
}

/** Server codes with a cause of their own. */
private object ServerCodes {
    /** `RequestRejected.serverCode` of a model name the server does not know (D60; set by `:network`). */
    const val MODEL_NOT_FOUND = "model_not_found"
}
