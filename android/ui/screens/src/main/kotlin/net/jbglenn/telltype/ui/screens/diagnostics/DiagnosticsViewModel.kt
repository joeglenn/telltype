package net.jbglenn.telltype.ui.screens.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import javax.inject.Inject
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
import net.jbglenn.telltype.domain.port.DebugBundleExporter
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.domain.port.SelfTest
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.ui.designsystem.error.connectionMessageRes
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.LocalTime
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/** Diagnostics: status, health, recent timings, self-test, debug bundle. */
@Suppress("LongParameterList") // One port per section of the screen; grouping them would hide what it uses.
class DiagnosticsViewModel @Inject constructor(
    private val permissions: PermissionStatusSource,
    private val health: ServerHealthSource,
    history: HistoryRepository,
    private val fixLauncher: FixLauncher,
    private val selfTest: SelfTest,
    private val exporter: DebugBundleExporter,
    time: LocalTime,
    settings: SettingsRepository,
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val testState = MutableStateFlow<SelfTestState>(SelfTestState.Idle)
    private val exporting = MutableStateFlow(false)
    private val effectChannel = Channel<DiagnosticsEffect>(Channel.BUFFERED)

    /** One-off effects: messages. */
    val effects: Flow<DiagnosticsEffect> = effectChannel.receiveAsFlow()

    /** What the screen shows. */
    val state: StateFlow<DiagnosticsUiState> = combine(
        combine(permissions.status, health.reachability, checking, ::Triple),
        history.recent(SESSION_COUNT),
        testState,
        exporting,
        settings.settings,
    ) { (permission, reachability, checkingNow), sessions, test, exportingNow, current ->
        DiagnosticsUiState(
            permissions = permission,
            reachability = reachability,
            checkingServer = checkingNow,
            models = current.server.models,
            sessions = sessions,
            speed = speedSummary(sessions),
            selfTest = test,
            exporting = exportingNow,
            zone = time.zone(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), DiagnosticsUiState(zone = time.zone()))

    /** Handles one user action. */
    fun onAction(action: DiagnosticsAction) {
        when (action) {
            is DiagnosticsAction.Fix -> viewModelScope.launch { fixLauncher.open(action.target) }
            DiagnosticsAction.CheckServer -> checkServer()
            DiagnosticsAction.RunSelfTest -> runSelfTest()
            DiagnosticsAction.Export -> export()
            DiagnosticsAction.Resumed -> viewModelScope.launch { permissions.refresh() }
        }
    }

    private fun checkServer() {
        if (checking.value) return
        checking.value = true
        viewModelScope.launch {
            try {
                health.probe()
            } finally {
                checking.value = false
            }
        }
    }

    private fun runSelfTest() {
        if (testState.value == SelfTestState.Running) return
        testState.value = SelfTestState.Running
        viewModelScope.launch {
            testState.value = when (val result = selfTest.run()) {
                is Outcome.Ok -> SelfTestState.Finished(result.value)
                is Outcome.Err -> SelfTestState.Failed(result.error.connectionMessageRes())
            }
        }
    }

    private fun export() {
        if (exporting.value) return
        exporting.value = true
        viewModelScope.launch {
            try {
                if (exporter.export() is Outcome.Err) {
                    effectChannel.send(DiagnosticsEffect.ShowMessage(R.string.diagnostics_export_failed))
                }
            } finally {
                exporting.value = false
            }
        }
    }

    private companion object {
        const val SESSION_COUNT = 20
    }
}
