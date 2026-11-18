package net.jbglenn.telltype.ui.screens.setup

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.ServerCertificateReader
import net.jbglenn.telltype.domain.port.ServerConnectionChecker

/**
 * Tests one address for Setup (D60, D25): HTTP as it is; HTTPS with the confirmed or stored pin, else with the
 * system's trust. When the system does not trust the certificate (or the pin no longer matches), it reads the
 * certificate without sending anything and asks for confirmation.
 */
internal class ServerTestRun(
    private val checker: ServerConnectionChecker,
    private val certificates: ServerCertificateReader,
) {
    /**
     * One address to test.
     *
     * @property endpoint the address.
     * @property roles what it must serve.
     * @property storedPin the pin stored for this same address, null when none.
     */
    data class Target(val endpoint: ServerEndpoint, val roles: Set<ServerRole>, val storedPin: CertificateFingerprint?)

    /** How one address's test ended. */
    sealed interface Result {
        /**
         * It answered.
         *
         * @property check what it answered.
         * @property pin the certificate to pin, null for the system's trust or HTTP.
         */
        data class Passed(val check: ServerCheck, val pin: CertificateFingerprint?) : Result

        /**
         * Its certificate needs the user's confirmation.
         *
         * @property fingerprint the certificate presented.
         * @property changed a different certificate was pinned for this address before.
         */
        data class Confirm(val fingerprint: CertificateFingerprint, val changed: Boolean) : Result

        /**
         * It failed.
         *
         * @property error why.
         */
        data class Failed(val error: NetworkError) : Result
    }

    /** Tests [target] with [key], trusting [confirmed] when the user has just confirmed that certificate. */
    suspend fun run(target: Target, key: ServerKey?, confirmed: CertificateFingerprint?): Result {
        val pin = if (target.endpoint.isPlainHttp) null else confirmed ?: target.storedPin
        return when (val checked = checker.check(ServerCandidate(target.endpoint, key, pin, target.roles))) {
            is Outcome.Ok -> Result.Passed(checked.value, pin)

            is Outcome.Err -> if (checked.error == CertificateMismatch && !target.endpoint.isPlainHttp &&
                confirmed == null
            ) {
                readFingerprint(target)
            } else {
                Result.Failed(checked.error)
            }
        }
    }

    private suspend fun readFingerprint(target: Target): Result =
        when (val read = certificates.presentedFingerprint(target.endpoint)) {
            is Outcome.Ok -> Result.Confirm(read.value, changed = target.storedPin != null)
            is Outcome.Err -> Result.Failed(read.error)
        }
}
