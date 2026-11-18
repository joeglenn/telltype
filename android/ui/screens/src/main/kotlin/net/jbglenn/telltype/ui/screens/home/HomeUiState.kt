package net.jbglenn.telltype.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import java.time.ZoneId
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.system.PermissionStatus
import net.jbglenn.telltype.ui.designsystem.error.ServerCause
import net.jbglenn.telltype.ui.designsystem.error.serverCause
import net.jbglenn.telltype.ui.screens.R

/**
 * Everything Home shows (docs/PLAN.md section 5.3): the status card, the bubble switch and snooze, today's
 * numbers and the recent dictations.
 *
 * @property loading nothing has arrived yet.
 * @property problems what stops or limits dictation, most serious first; empty means ready.
 * @property checkingServer a health probe is running.
 * @property bubbleEnabled the bubble switch.
 * @property snoozedUntil the bubble is snoozed until then; null when not snoozed.
 * @property stats today's words and the typical speed.
 * @property recent the latest dictations, newest first.
 * @property zone the zone times are shown in.
 */
@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val problems: List<HomeProblem> = emptyList(),
    val checkingServer: Boolean = false,
    val bubbleEnabled: Boolean = true,
    val snoozedUntil: Instant? = null,
    val stats: DictationStats = DictationStats(wordsToday = 0, typicalWordsPerMinute = null),
    val recent: List<HistoryEntry> = emptyList(),
    val zone: ZoneId = ZoneId.of("UTC"),
)

/**
 * One problem on the status card, with its message and where its button leads.
 *
 * @property message what is wrong, in a few words.
 * @property fix where Fix leads; null when the button checks the server again or there is nothing to press.
 * @property blocking dictation cannot work until it is fixed (the card turns to the error colours).
 */
enum class HomeProblem(@param:StringRes val message: Int, val fix: FixTarget?, val blocking: Boolean) {
    /** The accessibility service is off: no bubble. */
    ACCESSIBILITY_OFF(R.string.home_problem_accessibility, FixTarget.ACCESSIBILITY_SERVICE, blocking = true),

    /** No microphone permission. */
    MICROPHONE_DENIED(R.string.home_problem_microphone, FixTarget.MICROPHONE_PERMISSION, blocking = true),

    /** No server address, key or certificate yet. */
    SERVER_NOT_SET_UP(R.string.home_problem_server_setup, FixTarget.SERVER_SETUP, blocking = true),

    /** Android 17 blocks the home-network address. */
    LOCAL_NETWORK_DENIED(R.string.home_problem_local_network, FixTarget.LOCAL_NETWORK_PERMISSION, blocking = true),

    /** The last probe got no answer; recordings are queued meanwhile. */
    SERVER_UNREACHABLE(ServerCause.UNREACHABLE.message, fix = null, blocking = false),

    /** The server rejected the key (HTTP 401): Fix opens Setup. */
    SERVER_KEY_REJECTED(ServerCause.KEY_REJECTED.message, FixTarget.SERVER_SETUP, blocking = true),

    /** The server's certificate is not the confirmed one: Fix opens Setup. */
    SERVER_CERTIFICATE_CHANGED(ServerCause.CERTIFICATE_CHANGED.message, FixTarget.SERVER_SETUP, blocking = true),

    /** The server does not offer the chosen model. */
    SERVER_MODEL_MISSING(ServerCause.MODEL_MISSING.message, fix = null, blocking = false),

    /** The server answers but is still loading its models. */
    SERVER_WARMING_UP(ServerCause.WARMING_UP.message, fix = null, blocking = false),

    /** The server answered with an error. */
    SERVER_ERROR(ServerCause.SERVER_ERROR.message, fix = null, blocking = false),

    /** The server refused the check as such (app and server versions do not fit). */
    SERVER_REQUEST_REFUSED(ServerCause.REQUEST_REFUSED.message, fix = null, blocking = false),

    /** Advanced Protection limits accessibility services; text arrives through clipboard and History. */
    ADVANCED_PROTECTION(R.string.home_problem_advanced_protection, fix = null, blocking = true),

    /** Without notifications, queued results and hidden problems cannot be shown. */
    NOTIFICATIONS_OFF(R.string.home_problem_notifications, FixTarget.NOTIFICATION_PERMISSION, blocking = false),
}

/**
 * Today's numbers.
 *
 * @property wordsToday words dictated since midnight.
 * @property typicalWordsPerMinute the median speaking speed of recent dictations; null without enough data.
 */
@Immutable
data class DictationStats(val wordsToday: Int, val typicalWordsPerMinute: Int?)

/** What the user did on Home. */
sealed interface HomeAction {
    /**
     * Pressed the button of a problem.
     *
     * @property problem which one.
     */
    data class Fix(val problem: HomeProblem) : HomeAction

    /**
     * Switched the bubble on or off.
     *
     * @property enabled the new value.
     */
    data class SetBubbleEnabled(val enabled: Boolean) : HomeAction

    /** Snooze the bubble for an hour. */
    data object Snooze : HomeAction

    /** End the snooze. */
    data object EndSnooze : HomeAction

    /**
     * Opened a recent dictation.
     *
     * @property id its session id.
     */
    data class OpenEntry(val id: SessionId) : HomeAction

    /** Opened the full History. */
    data object OpenHistory : HomeAction

    /** The screen came to the front (re-read permissions and server health). */
    data object Resumed : HomeAction
}

/** One-off effects of Home. */
sealed interface HomeEffect {
    /** Go to setup (server not set up). */
    data object OpenSetup : HomeEffect

    /**
     * Tell the user something briefly.
     *
     * @property message the text.
     */
    data class ShowMessage(@param:StringRes val message: Int) : HomeEffect
}

/** The problems for this snapshot of the world, most serious first. */
fun homeProblems(
    permissions: PermissionStatus,
    serverConfigured: Boolean,
    reachability: ServerReachability,
): List<HomeProblem> = buildList {
    if (!permissions.accessibilityEnabled) add(HomeProblem.ACCESSIBILITY_OFF)
    if (permissions.advancedProtectionOn) add(HomeProblem.ADVANCED_PROTECTION)
    if (!permissions.microphoneGranted) add(HomeProblem.MICROPHONE_DENIED)
    if (!serverConfigured) add(HomeProblem.SERVER_NOT_SET_UP)
    if (!permissions.localNetworkGranted) add(HomeProblem.LOCAL_NETWORK_DENIED)
    if (serverConfigured && reachability is ServerReachability.Unreachable) {
        add(reachability.error.serverCause().homeProblem())
    }
    if (!permissions.notificationsGranted) add(HomeProblem.NOTIFICATIONS_OFF)
}.distinct()

/**
 * The status card's problem for a failed server check: the same [ServerCause] the bubble, Settings and Diagnostics
 * name (B9). Exhaustive, so a new cause needs a Home problem.
 */
fun ServerCause.homeProblem(): HomeProblem = when (this) {
    ServerCause.NOT_SET_UP -> HomeProblem.SERVER_NOT_SET_UP
    ServerCause.LOCAL_NETWORK_OFF -> HomeProblem.LOCAL_NETWORK_DENIED
    ServerCause.UNREACHABLE -> HomeProblem.SERVER_UNREACHABLE
    ServerCause.KEY_REJECTED -> HomeProblem.SERVER_KEY_REJECTED
    ServerCause.CERTIFICATE_CHANGED -> HomeProblem.SERVER_CERTIFICATE_CHANGED
    ServerCause.MODEL_MISSING -> HomeProblem.SERVER_MODEL_MISSING
    ServerCause.WARMING_UP -> HomeProblem.SERVER_WARMING_UP
    ServerCause.SERVER_ERROR -> HomeProblem.SERVER_ERROR
    ServerCause.REQUEST_REFUSED -> HomeProblem.SERVER_REQUEST_REFUSED
}

/**
 * Words dictated today (successful sessions since local midnight) and the median speaking speed of the
 * successful dictations of at least [SpeedDefaults.MIN_SPEED_SAMPLE_SECONDS] seconds among [entries].
 */
fun dictationStats(entries: List<HistoryEntry>, now: Instant, zone: ZoneId): DictationStats {
    val today = now.toJavaInstant().atZone(zone).toLocalDate()
    val successes = entries.filter { it.errorCode == null && it.finalText.isNotBlank() }
    val wordsToday = successes
        .filter { it.createdAt.toJavaInstant().atZone(zone).toLocalDate() == today }
        .sumOf { wordCount(it.finalText) }
    val speeds = successes
        .filter { it.audioDuration.inWholeSeconds >= SpeedDefaults.MIN_SPEED_SAMPLE_SECONDS }
        .map(::wordsPerMinute)
        .sorted()
    val median = if (speeds.isEmpty()) null else speeds[speeds.size / 2]
    return DictationStats(wordsToday = wordsToday, typicalWordsPerMinute = median?.toInt())
}

private fun wordsPerMinute(entry: HistoryEntry): Double {
    val words = wordCount(entry.rawTranscript.ifBlank { entry.finalText })
    return words * SpeedDefaults.SECONDS_PER_MINUTE * SpeedDefaults.MILLIS_PER_SECOND /
        entry.audioDuration.inWholeMilliseconds
}

private fun wordCount(text: String): Int = text.split(Whitespace).count { it.isNotBlank() }

private val Whitespace = Regex("\\s+")

/** Fixed values used in this file. */
private object SpeedDefaults {
    const val MIN_SPEED_SAMPLE_SECONDS = 2
    const val SECONDS_PER_MINUTE = 60.0
    const val MILLIS_PER_SECOND = 1000.0
}
