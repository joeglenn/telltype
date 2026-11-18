package net.jbglenn.telltype.runtime

import android.content.res.Resources
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.system.BubbleDrop
import net.jbglenn.telltype.core.model.system.BubblePlacement
import net.jbglenn.telltype.domain.coordinator.SessionCoordinator
import net.jbglenn.telltype.domain.port.AppRuleRepository
import net.jbglenn.telltype.domain.port.BubbleSnooze
import net.jbglenn.telltype.domain.port.FocusedFieldSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.platform.overlay.AccessibilityOverlayHost
import net.jbglenn.telltype.platform.overlay.OverlayTray
import net.jbglenn.telltype.ui.overlay.BubbleEvent
import net.jbglenn.telltype.ui.overlay.BubblePlacement as SnoozePlacement
import net.jbglenn.telltype.ui.overlay.SnoozeTarget

/**
 * The bubble for one coordinator: shows the overlay while the session state has a bubble (and the app in front is
 * not snoozed), sends gestures to the coordinator, moves the window on drags, shows the snooze band while dragging
 * and applies a drop on it. Chips are shown for [CHIP_SHOWN].
 */
internal class BubbleController @Inject constructor(
    private val host: AccessibilityOverlayHost,
    private val tray: OverlayTray,
    private val content: BubbleContent,
    private val snoozes: BubbleSnoozes,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) {
    /** Runs until cancelled, then removes both windows. */
    suspend fun run(coordinator: SessionCoordinator) {
        val chip = MutableStateFlow<Chip?>(null)
        val gestures = BubbleGestures(coordinator, snoozes, host, logger)
        try {
            coroutineScope {
                launch {
                    coordinator.chips.collectLatest { next ->
                        chip.value = next
                        delay(CHIP_SHOWN)
                        chip.value = null
                    }
                }
                launch { snoozes.followFocus() }
                launch { snoozes.resets().collect { reason -> host.resetPlacement(reason) } }
                launch {
                    host.placement.map { it?.dragging == true }.distinctUntilChanged().collectLatest { dragging ->
                        if (dragging) tray.show { content.Tray(host.placement, snoozes::targetAt) } else tray.hide()
                    }
                }
                val visible = combine(coordinator.state, snoozes.appSnoozed, host.keyboardOpen, ::Visibility)
                visible.distinctUntilChangedBy { it.shown }.collectLatest { change ->
                    logger.info(TAG) { "bubble ${change.describe()} (app ${snoozes.focusedApp() ?: "none"})" }
                    if (change.shown) show(coordinator, chip, gestures) else host.hide()
                }
            }
        } finally {
            withContext(NonCancellable + dispatchers.main) {
                tray.hide()
                host.hide(fade = false)
            }
        }
    }

    /**
     * The session's states, each with whether the bubble shows for it (the state has a bubble and the app in front
     * is not snoozed for "this app").
     */
    fun shownStates(coordinator: SessionCoordinator): Flow<Pair<SessionState, Boolean>> =
        combine(coordinator.state, snoozes.appSnoozed, host.keyboardOpen) { state, snoozed, keyboard ->
            state to Visibility(state, snoozed, keyboard).shown
        }

    /**
     * Whether the bubble shows for [state] with the app in front [snoozed] for "this app" and the [keyboard] open
     * or not ([SessionState.needsKeyboard]), and why, for the log.
     */
    private data class Visibility(val state: SessionState, val snoozed: Boolean, val keyboard: Boolean) {
        private val keyboardMissing: Boolean get() = state.needsKeyboard() && !keyboard

        val shown: Boolean get() = state.showsBubble() && !snoozed && !keyboardMissing

        /** The decision and its reason: the state's kind (never its text), its focus, the app snooze, the keyboard. */
        fun describe(): String {
            val kind = state::class.simpleName
            return when {
                shown -> "shown: state $kind"
                snoozed && state.showsBubble() -> "hidden: app snoozed (state $kind)"
                keyboardMissing && state.showsBubble() -> "hidden: no keyboard (state $kind)"
                else -> "hidden: state $kind has no bubble here"
            }
        }
    }

    private suspend fun show(coordinator: SessionCoordinator, chip: MutableStateFlow<Chip?>, gestures: BubbleGestures) {
        val side = snoozes.side()
        val shown = host.show(side) { content.Bubble(coordinator, chip, gestures::onEvent) }
        if (shown is Outcome.Err) logger.warn(TAG, null) { "bubble window refused: ${shown.error.code}" }
    }

    /** Gestures from the bubble: session events to the coordinator, drags to the window, drops to the snoozes. */
    private class BubbleGestures(
        private val coordinator: SessionCoordinator,
        private val snoozes: BubbleSnoozes,
        private val host: AccessibilityOverlayHost,
        private val logger: Logger,
    ) {
        private var lastPlacement: BubblePlacement? = null

        fun onEvent(event: BubbleEvent) {
            when (event) {
                is BubbleEvent.Session -> coordinator.dispatch(event.event)

                is BubbleEvent.Moved -> {
                    snoozes.fingerX = event.x
                    host.dragFinger(event.x, event.y, event.grabX, event.grabY)
                    lastPlacement = host.placement.value
                }

                BubbleEvent.MoveEnded -> {
                    val dropped = lastPlacement
                    lastPlacement = null
                    if (host.dragEnded() == BubbleDrop.SnoozeZone) {
                        val target = dropped?.let(snoozes::targetAt)
                        logger.info(TAG) { "bubble dropped on the snooze band: ${target ?: "between the targets"}" }
                        target?.let(snoozes::apply)
                    }
                    snoozes.fingerX = null
                }

                is BubbleEvent.Resized -> snoozes.bubbleWidth = event.width

                BubbleEvent.SnoozeRequested -> snoozes.apply(SnoozeTarget.ONE_HOUR)

                BubbleEvent.NeverShowInThisApp -> snoozes.apply(SnoozeTarget.NEVER_IN_THIS_APP)

                is BubbleEvent.HoldFeedback, BubbleEvent.MenuRequested, BubbleEvent.MenuDismissed -> Unit
            }
        }
    }

    companion object {
        /** How long a one-off chip (Busy, Explanation, Learn offer...) stays on the bubble. */
        val CHIP_SHOWN = 4.seconds
        private val TAG = LogTag("Bubble")
    }
}

/**
 * The three snooze targets (docs/PLAN.md section 5.2): an hour everywhere ([BubbleSnooze]), the app in front until
 * another app has the focus (kept here, for this connection), and never in this app (an excluded [AppRule]).
 */
internal class BubbleSnoozes @Inject constructor(
    private val snooze: BubbleSnooze,
    private val focused: FocusedFieldSource,
    private val appRules: AppRuleRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val logger: Logger,
) {
    private val snoozedApp = MutableStateFlow<AppPackage?>(null)
    private val requests = MutableStateFlow<SnoozeTarget?>(null)

    /** The bubble's last measured width, for the drop position. Main thread only. */
    @Volatile
    var bubbleWidth: Int = 0

    /** Where the dragging finger is (screen x), null when no drag reported one. Main thread only. */
    @Volatile
    var fingerX: Float? = null

    /** True while the app in front is snoozed with "this app". */
    val appSnoozed = combine(snoozedApp, focused.focused) { app, field -> app != null && field?.target?.app == app }

    /** The docking side from the settings. */
    suspend fun side() = settings.current().bubble.side

    /** The package of the app with the focused field, for the log; null without one. */
    fun focusedApp(): String? = focused.focused.value?.target?.app?.name

    /**
     * Why the bubble should go back to its default place, as it happens: the bubble switched on again in the app,
     * or an hour snooze that ended (Resume on Home). Each also forgets a "this app" snooze, so that the user's way
     * back never depends on knowing what went wrong (board B1).
     */
    fun resets(): Flow<String> = merge(
        settings.settings.map { it.bubble.enabled }.distinctUntilChanged().drop(1).filter { it }
            .map { "bubble switched on" },
        snooze.snoozedUntil.map { it != null }.distinctUntilChanged().drop(1).filter { snoozed -> !snoozed }
            .map { "snooze ended" },
    ).onEach { snoozedApp.value = null }

    /**
     * The target under the finger dragging the bubble at [placement], or null outside the band. The window host
     * decides whether the finger is inside the band (it knows the band's height); the finger's column picks the
     * target (the bubble's centre when no finger was reported).
     */
    fun targetAt(placement: BubblePlacement): SnoozeTarget? {
        if (!placement.inSnoozeZone) return null
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels.toFloat()
        val column = fingerX ?: (placement.x + bubbleWidth / 2f)
        return SnoozePlacement.snoozeTargetAt(column, centreY = 1f, screenWidth, screenHeight = 1f, zoneHeight = 1f)
    }

    /** Queues [target] for [followFocus], which applies it. */
    fun apply(target: SnoozeTarget) {
        requests.value = target
    }

    /**
     * Applies queued targets and ends an app snooze when another app has the focus. Runs until cancelled, once per
     * connection of the service: it starts by forgetting an app snooze of the previous connection (this object
     * lives as long as the process, and the snooze is meant for one connection only).
     */
    suspend fun followFocus() {
        snoozedApp.value = null
        coroutineScope {
            launch {
                focused.focused.collect { field ->
                    val app = field?.target?.app
                    if (app != null && app != snoozedApp.value) snoozedApp.value = null
                }
            }
            requests.collect { target ->
                if (target != null) {
                    requests.value = null
                    applyNow(target)
                }
            }
        }
    }

    private suspend fun applyNow(target: SnoozeTarget) {
        val app = focused.focused.value?.target?.app
        logger.info(TAG) { "snooze applied: $target (app ${app?.name ?: "none"})" }
        when (target) {
            SnoozeTarget.ONE_HOUR -> snooze.snoozeUntil(clock.now() + 1.hours)

            SnoozeTarget.THIS_APP -> snoozedApp.value = app

            SnoozeTarget.NEVER_IN_THIS_APP -> if (app != null) {
                val saved = appRules.upsert(AppRule(app, categoryOverride = null, toneOverride = null, excluded = true))
                if (saved is Outcome.Err) logger.warn(TAG, null) { "exclusion not saved: ${saved.error.code}" }
            }
        }
    }

    private companion object {
        val TAG = LogTag("BubbleSnoozes")
    }
}
