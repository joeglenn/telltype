package net.jbglenn.telltype.network.http

import java.io.IOException

/** The result of [send]: an answer read by the caller, or the I/O error that prevented one. */
internal sealed interface Sent<out T> {
    /** The server answered and the reader produced [value]. */
    data class Answered<out T>(val value: T) : Sent<T>

    /**
     * No answer: connecting, the handshake, the upload or reading failed with [error].
     *
     * @property connected the call had an established connection when it failed ([ConnectionProgress]).
     */
    data class Failed(val error: IOException, val connected: Boolean) : Sent<Nothing>
}
