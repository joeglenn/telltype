package net.jbglenn.telltype.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.domain.port.BubbleSnooze
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.LocalTime
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/** Home: status, bubble switch and snooze, today's numbers, recent dictations. */
@Suppress("LongParameterList") // One port per concern on this screen; grouping them would hide what it uses.
class HomeViewModel @Inject constructor(
    private val permissions: PermissionStatusSource,
    private val health: ServerHealthSource,
    private val settings: SettingsRepository,
    history: HistoryRepository,
    private val snooze: BubbleSnooze,
    private val fixLauncher: FixLauncher,
    private val time: LocalTime,
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val effectChannel = Channel<HomeEffect>(Channel.BUFFERED)

    /** One-off effects: navigation to setup and short messages. */
    val effects: Flow<HomeEffect> = effectChannel.receiveAsFlow()

    /** What Home shows. */
    val state: StateFlow<HomeUiState> = combine(
        combine(permissions.status, health.reachability, settings.settings, ::Triple),
        snooze.snoozedUntil,
        history.recent(STATS_WINDOW),
        checking,
    ) { (permission, reachability, current), snoozedUntil, entries, checkingNow ->
        HomeUiState(
            loading = false,
            problems = homeProblems(permission, current.server.isConfigured, reachability),
            checkingServer = checkingNow,
            bubbleEnabled = current.bubble.enabled,
            snoozedUntil = snoozedUntil,
            stats = dictationStats(entries, time.now(), time.zone()),
            recent = entries.take(RECENT_COUNT),
            zone = time.zone(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), HomeUiState(zone = time.zone()))

    /** Handles one user action. */
    fun onAction(action: HomeAction) {
        when (action) {
            is HomeAction.Fix -> fix(action.problem)
            is HomeAction.SetBubbleEnabled -> setBubbleEnabled(action.enabled)
            HomeAction.Snooze -> viewModelScope.launch { snooze.snoozeUntil(time.now() + SnoozeLength) }
            HomeAction.EndSnooze -> viewModelScope.launch { snooze.resume() }
            HomeAction.Resumed -> refresh()
            is HomeAction.OpenEntry, HomeAction.OpenHistory -> Unit
        }
    }

    private fun fix(problem: HomeProblem) {
        viewModelScope.launch {
            when (val target = problem.fix) {
                FixTarget.SERVER_SETUP -> effectChannel.send(HomeEffect.OpenSetup)
                null -> checkServer()
                else -> fixLauncher.open(target)
            }
        }
    }

    private fun setBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            val result = settings.update { it.copy(bubble = it.bubble.copy(enabled = enabled)) }
            if (result is Outcome.Err) effectChannel.send(HomeEffect.ShowMessage(R.string.message_save_failed))
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            permissions.refresh()
            if (settings.current().server.isConfigured) checkServer()
        }
    }

    private suspend fun checkServer() {
        checking.value = true
        try {
            health.probe()
        } finally {
            checking.value = false
        }
    }

    private companion object {
        const val STATS_WINDOW = 200
        const val RECENT_COUNT = 5
        val SnoozeLength = 1.hours
    }
}
