package net.jbglenn.telltype.runtime

import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.jbglenn.telltype.domain.coordinator.SessionCoordinator

/**
 * What runs for one coordinator while the accessibility service is connected: the bubble ([BubbleController]),
 * the network watch ([NetworkWatcher]) and the notice for a problem the hidden bubble cannot show
 * ([RuntimeNotices.problemsWhileHidden]).
 */
internal class ConnectedWork @Inject constructor(
    private val bubble: BubbleController,
    private val network: NetworkWatcher,
    private val notices: RuntimeNotices,
) {
    /** Runs until cancelled; the bubble removes its windows on the way out. */
    suspend fun run(coordinator: SessionCoordinator) {
        coroutineScope {
            launch { network.watch() }
            launch { notices.problemsWhileHidden(bubble.shownStates(coordinator)) }
            bubble.run(coordinator)
        }
    }
}
