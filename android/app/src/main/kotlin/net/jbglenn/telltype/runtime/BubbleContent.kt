package net.jbglenn.telltype.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.system.BubblePlacement
import net.jbglenn.telltype.domain.coordinator.SessionCoordinator
import net.jbglenn.telltype.domain.port.AudioCapture
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.platform.overlay.AccessibilityOverlayHost
import net.jbglenn.telltype.ui.designsystem.TelltypeTheme
import net.jbglenn.telltype.ui.overlay.BubbleEvent
import net.jbglenn.telltype.ui.overlay.BubbleOverlay
import net.jbglenn.telltype.ui.overlay.LocalWindowAtRest
import net.jbglenn.telltype.ui.overlay.SnoozeTarget
import net.jbglenn.telltype.ui.overlay.SnoozeTray
import net.jbglenn.telltype.ui.overlay.rememberWaveformLevels

/**
 * The Compose content of the two overlay windows, reading the coordinator, the server health, the levels, the idle
 * bubble's look from the settings and whether the bubble's window is at rest.
 */
internal class BubbleContent @Inject constructor(
    private val health: ServerHealthSource,
    private val audio: AudioCapture,
    private val host: AccessibilityOverlayHost,
    private val settings: SettingsRepository,
) {
    private val looks = settings.settings.map { it.bubble.look }.distinctUntilChanged()

    /**
     * The bubble: state, offline mark, chip, the edge it is docked to now and live levels; its controls follow the
     * window's rest. The side is the window's, not the settings': after a drag to the other edge the content grows
     * from the edge the window is anchored to.
     */
    @Composable
    fun Bubble(coordinator: SessionCoordinator, chip: StateFlow<Chip?>, onEvent: (BubbleEvent) -> Unit) {
        val state by coordinator.state.collectAsState()
        val reachability by health.reachability.collectAsState()
        val currentChip by chip.collectAsState()
        val look by looks.collectAsState(initial = BubbleLook.Default)
        TelltypeTheme {
            CompositionLocalProvider(LocalWindowAtRest provides host.windowAtRest) {
                BubbleOverlay(
                    state = state,
                    reachability = reachability,
                    chip = currentChip,
                    side = host.dockedSide.value,
                    levels = rememberWaveformLevels(audio.levels),
                    onEvent = onEvent,
                    look = look,
                )
            }
        }
    }

    /** The snooze band, highlighting the target under the dragged bubble ([targetAt]). */
    @Composable
    fun Tray(placement: StateFlow<BubblePlacement?>, targetAt: (BubblePlacement) -> SnoozeTarget?) {
        val current by placement.collectAsState()
        TelltypeTheme { SnoozeTray(highlighted = current?.let(targetAt)) }
    }
}
