package net.jbglenn.telltype.ui.screens.home

import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.LocalNetworkPermissionMissing
import net.jbglenn.telltype.core.model.error.MalformedResponse
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerFailed
import net.jbglenn.telltype.core.model.error.ServerNotConfigured
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.designsystem.error.ServerCause
import net.jbglenn.telltype.ui.designsystem.error.connectionMessageRes
import net.jbglenn.telltype.ui.designsystem.error.messageRes
import net.jbglenn.telltype.ui.designsystem.error.serverCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B9: for each server failure, Home's status card, the bubble's Problem state (and History, both
 * `DictationError.messageRes`) and Settings, Diagnostics and Setup (`connectionMessageRes`) name the same cause.
 */
class ServerCauseConsistencyTest {
    private val cases: List<Pair<NetworkError, ServerCause>> = listOf(
        KeyRejected to ServerCause.KEY_REJECTED,
        ServerUnreachable to ServerCause.UNREACHABLE,
        CertificateMismatch to ServerCause.CERTIFICATE_CHANGED,
        RequestRejected(httpStatus = 400, serverCode = "model_not_found") to ServerCause.MODEL_MISSING,
        ServerFailed(httpStatus = 502, serverCode = "backend_error") to ServerCause.SERVER_ERROR,
        MalformedResponse to ServerCause.SERVER_ERROR,
        ServerWarmingUp to ServerCause.WARMING_UP,
        RequestRejected(httpStatus = 413, serverCode = "too_large") to ServerCause.REQUEST_REFUSED,
        ServerNotConfigured to ServerCause.NOT_SET_UP,
        LocalNetworkPermissionMissing to ServerCause.LOCAL_NETWORK_OFF,
    )

    @Test
    fun everySurfaceNamesTheSameCause() {
        for ((error, cause) in cases) {
            assertEquals(error.code, cause, error.serverCause())
            val home = homeProblems(
                Fixtures.permissionsGranted(),
                serverConfigured = true,
                reachability = ServerReachability.Unreachable(error, Fixtures.Epoch),
            )
            assertEquals(error.code, listOf(cause.homeProblem()), home)
            if (cause != ServerCause.LOCAL_NETWORK_OFF && cause != ServerCause.NOT_SET_UP) {
                // Home names these two in rows of their own (Setup, permission), each with its Fix.
                assertEquals("Home: ${error.code}", cause.message, home.single().message)
            }
            assertEquals("Settings, Diagnostics, Setup: ${error.code}", cause.detail, error.connectionMessageRes())
            if (error != MalformedResponse) {
                assertEquals("bubble and History: ${error.code}", cause.message, error.messageRes())
            }
        }
    }

    @Test
    fun aCauseThatNeedsSetupIsBlockingAndOpensSetup() {
        for (cause in ServerCause.entries) {
            val problem = cause.homeProblem()
            val needsSetup = cause in setOf(
                ServerCause.NOT_SET_UP,
                ServerCause.KEY_REJECTED,
                ServerCause.CERTIFICATE_CHANGED,
            )
            val blocking = needsSetup || cause == ServerCause.LOCAL_NETWORK_OFF
            assertEquals("$cause", blocking, problem.blocking)
            assertTrue("$cause", needsSetup == (problem.fix == FixTarget.SERVER_SETUP))
        }
    }
}
