package net.jbglenn.telltype.runtime

import javax.inject.Inject
import javax.inject.Provider
import net.jbglenn.telltype.domain.coordinator.CoordinatorEnvironment
import net.jbglenn.telltype.domain.coordinator.CoordinatorPorts
import net.jbglenn.telltype.domain.coordinator.CoordinatorStores
import net.jbglenn.telltype.domain.coordinator.DefaultSessionCoordinator
import net.jbglenn.telltype.domain.coordinator.FocusSources
import net.jbglenn.telltype.domain.coordinator.SessionCoordinator

/** Builds one coordinator per accessibility-service connection (contracts.md page 9). */
internal class SessionCoordinatorFactory @Inject constructor(
    private val ports: Provider<CoordinatorPorts>,
    private val stores: Provider<CoordinatorStores>,
    private val focus: Provider<FocusSources>,
    private val environment: Provider<CoordinatorEnvironment>,
) {
    /** A new, not yet started coordinator. */
    fun create(): SessionCoordinator =
        DefaultSessionCoordinator(ports.get(), stores.get(), focus.get(), environment.get())
}
