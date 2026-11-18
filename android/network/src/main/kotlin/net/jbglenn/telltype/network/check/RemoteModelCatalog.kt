package net.jbglenn.telltype.network.check

import javax.inject.Inject
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.NetworkError
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.ServerModelCatalog
import net.jbglenn.telltype.network.route.Attempt
import net.jbglenn.telltype.network.route.CallPolicy
import net.jbglenn.telltype.network.route.ServerRouter

/**
 * The stored server's models for Settings (D58, D60): `GET /v1/models` at the role's address; for text without a
 * list, the cleanup and command models our gate's `/healthz` names when it answers; otherwise an empty list.
 */
internal class RemoteModelCatalog @Inject constructor(
    private val router: ServerRouter,
    private val probes: AddressProbes,
) : ServerModelCatalog {
    override suspend fun list(role: ServerRole): Outcome<List<OfferedModel>, NetworkError> =
        router.execute(role, CallPolicy.HEALTH) { target ->
            when (val listed = probes.models(target)) {
                is Attempt.Failure -> listed

                is Attempt.Success -> {
                    val models = listed.value.models
                    val health = if (role == ServerRole.TEXT && models == null) {
                        (probes.gateHealth(target) as? Attempt.Success)?.value
                    } else {
                        null
                    }
                    Attempt.Success(models ?: health?.textModels().orEmpty())
                }
            }
        }
}
