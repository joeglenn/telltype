package net.jbglenn.telltype.ui.overlay

import kotlin.time.Duration
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.ServerReachability

/**
 * What [mapBubble] needs besides the session state.
 *
 * @property state the coordinator's session state.
 * @property reachability the latest health probe, for the offline mark.
 * @property timeInState how long the state has had its current kind (Listening, Working, Done...): drives the
 *   timer after 10 s, the Working label after 1.5 s and Done's chips after the check mark.
 * @property side the docking edge.
 * @property chip the transient chip being shown, if any.
 * @property releaseToCancel the held finger has slid away (reported by the bubble's gestures).
 * @property menuOpen the bubble menu was requested ([BubbleEvent.MenuRequested]) and not closed yet. It is shown
 *   only while the bubble is Unavailable, and hides the transient chip while open.
 */
data class BubbleInput(
    val state: SessionState,
    val reachability: ServerReachability,
    val timeInState: Duration,
    val side: BubbleSide,
    val chip: Chip? = null,
    val releaseToCancel: Boolean = false,
    val menuOpen: Boolean = false,
)
