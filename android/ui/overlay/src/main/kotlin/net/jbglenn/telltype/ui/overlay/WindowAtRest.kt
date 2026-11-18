package net.jbglenn.telltype.ui.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.flow.first

/**
 * Whether the overlay window holding the bubble is at rest: its frame has caught up with the content's size and
 * place. The window host provides it; outside an overlay window (previews, tests) it is always true.
 */
@Suppress("CompositionLocalAllowlist") // ambient to every control of the overlay window, like LocalView
val LocalWindowAtRest = staticCompositionLocalOf<State<Boolean>> { AlwaysAtRest }

private val AlwaysAtRest: State<Boolean> = mutableStateOf(true)

/**
 * Whether a button of the bubble may be in the accessibility tree. It is kept out from the frame it first appears
 * in (the window may be about to grow for it) until a later frame finds the window at rest, and again whenever the
 * window is not at rest: meanwhile its node would report where the button is in a window frame that is about to
 * change, and a tap there (UI Automator, TalkBack's explore by touch) lands beside it.
 */
@Composable
internal fun rememberExposedAtRest(): Boolean {
    val window by rememberUpdatedState(LocalWindowAtRest.current)
    var settledOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        snapshotFlow { window.value }.first { it }
        settledOnce = true
    }
    return settledOnce && window.value
}

/**
 * Removes this element and its content from the accessibility tree while not [exposed] (see
 * [rememberExposedAtRest]): no node with its label exists until then. Touches are unaffected.
 */
internal fun Modifier.exposedWhen(exposed: Boolean): Modifier = if (exposed) this else clearAndSetSemantics { }
