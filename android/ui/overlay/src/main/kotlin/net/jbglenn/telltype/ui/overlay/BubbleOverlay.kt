package net.jbglenn.telltype.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.ServerReachability

/**
 * The bubble wired to the session for the overlay window: measures how long the state has had its kind (on the
 * frame clock, re-mapping only when [nextBubbleChange] says the picture changes), keeps the hold-to-talk
 * feedback and whether the long-press menu is open (it closes on a choice, on Close, and when the state changes
 * kind), and maps everything with [mapBubble]. Every event, including [BubbleEvent.HoldFeedback] and the menu
 * events, is still passed to [onEvent].
 *
 * @param look the idle bubble's look from the settings (Ready only).
 */
@Composable
fun BubbleOverlay(
    state: SessionState,
    reachability: ServerReachability,
    chip: Chip?,
    side: BubbleSide,
    levels: WaveformLevels,
    onEvent: (BubbleEvent) -> Unit,
    modifier: Modifier = Modifier,
    look: BubbleLook = BubbleLook.Default,
) {
    var releaseToCancel by remember { mutableStateOf(false) }
    var menuOpen by remember(state::class) { mutableStateOf(false) }
    val timeInState = rememberTimeInState(state)
    val scene = mapBubble(BubbleInput(state, reachability, timeInState, side, chip, releaseToCancel, menuOpen))
        .copy(look = look)
    Bubble(
        scene = scene,
        onEvent = { event ->
            when (event) {
                is BubbleEvent.HoldFeedback -> releaseToCancel = event.releaseToCancel

                BubbleEvent.MenuRequested -> menuOpen = true

                BubbleEvent.MenuDismissed, BubbleEvent.SnoozeRequested, BubbleEvent.NeverShowInThisApp ->
                    menuOpen = false

                else -> Unit
            }
            onEvent(event)
        },
        levels = levels,
        modifier = modifier,
    )
}

/** Time since [state] took its current kind, updated only when the bubble's picture would change. */
@Composable
private fun rememberTimeInState(state: SessionState): Duration {
    val kind = state::class
    var elapsed by remember(kind) { mutableStateOf(Duration.ZERO) }
    val currentState by rememberUpdatedState(state)
    LaunchedEffect(kind) {
        val start = withFrameMillis { it }
        while (true) {
            val wait = nextBubbleChange(currentState, elapsed) ?: break
            delay(wait)
            elapsed = (withFrameMillis { it } - start).milliseconds
        }
    }
    return elapsed
}
