package net.jbglenn.telltype.testing

import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FakeServerPortsTest {
    private val candidate = ServerCandidate(
        endpoint = ServerEndpoint("192.0.2.10", 8000, Scheme.HTTP),
        key = null,
        certificate = null,
        roles = setOf(ServerRole.SPEECH, ServerRole.TEXT),
    )

    @Test
    fun `checker passes every role by the model list and records the candidate`() = runTest {
        val checker = FakeServerConnectionChecker()

        val check = (checker.check(candidate) as Outcome.Ok).value

        assertEquals(candidate.endpoint, check.endpoint)
        assertEquals(candidate.roles.associateWith { ServerCheckMethod.MODELS }, check.methods)
        assertEquals(FakeServerConnectionChecker.DefaultModels, check.models)
        assertEquals(listOf(candidate), checker.checkCall.calls)
    }

    @Test
    fun `checker and catalog can be scripted to fail`() = runTest {
        val checker = FakeServerConnectionChecker().apply { checkCall.returns(Outcome.Err(ServerUnreachable)) }
        val catalog = FakeServerModelCatalog().apply { listCall.returns(Outcome.Err(ServerUnreachable)) }

        assertEquals(Outcome.Err(ServerUnreachable), checker.check(candidate))
        assertEquals(Outcome.Err(ServerUnreachable), catalog.list(ServerRole.TEXT))
    }

    @Test
    fun `catalog lists the default models and records the role`() = runTest {
        val catalog = FakeServerModelCatalog()

        assertEquals(Outcome.Ok(FakeServerConnectionChecker.DefaultModels), catalog.list(ServerRole.TEXT))
        assertEquals(listOf(ServerRole.TEXT), catalog.listCall.calls)
    }
}
