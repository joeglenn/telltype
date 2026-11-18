package net.jbglenn.telltype.testing

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.ServerConnectionChecker
import net.jbglenn.telltype.domain.port.ServerModelCatalog

/** Connection checker fake. By default every address passes by `/v1/models` and lists [DefaultModels]. */
public class FakeServerConnectionChecker : ServerConnectionChecker {
    /** [check]: the argument is the candidate. */
    public val checkCall: ScriptedCall<ServerCandidate, Outcome<ServerCheck, NetworkError>> =
        ScriptedCall { candidate ->
            Outcome.Ok(
                ServerCheck(
                    endpoint = candidate.endpoint,
                    methods = candidate.roles.associateWith { ServerCheckMethod.MODELS },
                    models = DefaultModels,
                    health = null,
                ),
            )
        }

    override suspend fun check(candidate: ServerCandidate): Outcome<ServerCheck, NetworkError> =
        checkCall.answer(candidate)

    public companion object {
        /** What every address lists unless scripted otherwise. */
        public val DefaultModels: List<OfferedModel> = listOf(OfferedModel("model-a"), OfferedModel("model-b"))
    }
}

/** Model catalog fake. By default every role lists [FakeServerConnectionChecker.DefaultModels]. */
public class FakeServerModelCatalog : ServerModelCatalog {
    /** [list]: the argument is the role. */
    public val listCall: ScriptedCall<ServerRole, Outcome<List<OfferedModel>, NetworkError>> =
        ScriptedCall { Outcome.Ok(FakeServerConnectionChecker.DefaultModels) }

    override suspend fun list(role: ServerRole): Outcome<List<OfferedModel>, NetworkError> = listCall.answer(role)
}
