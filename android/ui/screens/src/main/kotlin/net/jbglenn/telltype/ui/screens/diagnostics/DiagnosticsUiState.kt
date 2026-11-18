package net.jbglenn.telltype.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.time.ZoneId
import kotlin.time.Duration
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.system.PermissionStatus
import net.jbglenn.telltype.domain.port.SelfTestReport

/**
 * Diagnostics (docs/PLAN.md section 13): permissions with Fix, the server's health, the last 20 sessions with their
 * timings, typical and worst speed, the self-test and the debug bundle.
 *
 * @property permissions the permission snapshot; null while loading.
 * @property reachability the latest health probe.
 * @property checkingServer a probe is running.
 * @property models the model names chosen in Settings, to compare with what the server lists.
 * @property sessions the last 20 sessions, newest first.
 * @property speed typical and worst stop-to-text time of [sessions].
 * @property selfTest the self-test's state.
 * @property exporting the debug bundle is being written.
 * @property zone the zone times are shown in.
 */
@Immutable
data class DiagnosticsUiState(
    val permissions: PermissionStatus? = null,
    val reachability: ServerReachability = ServerReachability.Unknown,
    val checkingServer: Boolean = false,
    val models: ServerModels = ServerModels.Unset,
    val sessions: List<HistoryEntry> = emptyList(),
    val speed: SpeedSummary? = null,
    val selfTest: SelfTestState = SelfTestState.Idle,
    val exporting: Boolean = false,
    val zone: ZoneId = ZoneId.of("UTC"),
)

/**
 * Stop-to-text times.
 *
 * @property typical the median.
 * @property worst the longest.
 */
@Immutable
data class SpeedSummary(val typical: Duration, val worst: Duration)

/** The self-test. */
@Immutable
sealed interface SelfTestState {
    /** Not run yet. */
    data object Idle : SelfTestState

    /** Running. */
    data object Running : SelfTestState

    /**
     * Finished.
     *
     * @property report the result.
     */
    data class Finished(val report: SelfTestReport) : SelfTestState

    /**
     * A stage failed.
     *
     * @property message why.
     */
    data class Failed(@param:StringRes val message: Int) : SelfTestState
}

/** What the user did on Diagnostics. */
sealed interface DiagnosticsAction {
    /**
     * Pressed Fix on a permission.
     *
     * @property target where it leads.
     */
    data class Fix(val target: FixTarget) : DiagnosticsAction

    /** Probe the server now. */
    data object CheckServer : DiagnosticsAction

    /** Run the self-test. */
    data object RunSelfTest : DiagnosticsAction

    /** Export the debug bundle. */
    data object Export : DiagnosticsAction

    /** The screen came to the front (re-read permissions). */
    data object Resumed : DiagnosticsAction
}

/** One-off effects of Diagnostics. */
sealed interface DiagnosticsEffect {
    /**
     * A short message.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : DiagnosticsEffect
}

/** Median and longest stop-to-text time of the sessions that measured one; null when none did. */
fun speedSummary(sessions: List<HistoryEntry>): SpeedSummary? {
    val times = sessions.mapNotNull { it.timings.endToEnd }.sorted()
    if (times.isEmpty()) return null
    return SpeedSummary(typical = times[times.size / 2], worst = times.last())
}
