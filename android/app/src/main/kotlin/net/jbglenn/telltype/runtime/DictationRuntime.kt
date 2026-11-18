package net.jbglenn.telltype.runtime

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.platform.service.AccessibilityServiceBridge

/**
 * Runs dictation while the accessibility service is connected (docs/PLAN.md sections 7 and 9): on connect it builds
 * and starts a [SessionCoordinator][net.jbglenn.telltype.domain.coordinator.SessionCoordinator], shows the
 * bubble ([BubbleController]) and watches the network ([ConnectedWork]); on disconnect all of that is cancelled,
 * the windows are removed and the coordinator is closed (which stops a running recording and the microphone
 * service). A new connection starts from scratch. [RuntimeNotices] posts what the bubble cannot show.
 */
@Singleton
internal class DictationRuntime @Inject constructor(
    private val bridge: AccessibilityServiceBridge,
    private val coordinators: SessionCoordinatorFactory,
    private val work: ConnectedWork,
    private val notices: RuntimeNotices,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    /** Follows the service's connection for as long as [scope] lives. */
    fun start(scope: CoroutineScope) {
        scope.launch(dispatchers.main) {
            bridge.connected.collectLatest { connected -> if (connected) runWhileConnected() }
        }
        scope.launch(dispatchers.main) { notices.accessibilityOff(bridge.connected) }
    }

    private suspend fun runWhileConnected() {
        logger.info(TAG) { "service connected: starting the coordinator" }
        // Resolving the coordinator's graph opens files and clients: never on the main thread.
        val coordinator = withContext(dispatchers.default) { coordinators.create() }
        coordinator.start()
        try {
            work.run(coordinator)
        } finally {
            coordinator.close()
            logger.info(TAG) { "service disconnected: coordinator closed" }
        }
    }

    private companion object {
        val TAG = LogTag("Runtime")
    }
}
