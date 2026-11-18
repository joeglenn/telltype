package net.jbglenn.telltype.ui.screens.setup

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.domain.port.ServerCertificateReader
import net.jbglenn.telltype.domain.port.ServerConnectionChecker
import net.jbglenn.telltype.domain.port.ServerHealthSource
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.common.StopTimeout

/**
 * Setup: connect to the server (D60: one address, an optional text model address under Advanced, an optional key;
 * HTTPS certificates the system does not trust are confirmed by fingerprint), then the microphone, notifications
 * and accessibility, then practice. Test checks every address, then saves them with the default models
 * ([chooseModels]). Permissions are re-read on every resume.
 */
class SetupViewModel @Inject constructor(
    private val settings: SettingsRepository,
    certificates: ServerCertificateReader,
    checker: ServerConnectionChecker,
    private val health: ServerHealthSource,
    private val permissions: PermissionStatusSource,
    private val fixLauncher: FixLauncher,
) : ViewModel() {
    private val form = MutableStateFlow(SetupUiState())
    private val effectChannel = Channel<SetupEffect>(Channel.BUFFERED)
    private val runner = ServerTestRun(checker, certificates)
    private var pending: PendingTest? = null

    /** One-off effects: setup finished. */
    val effects: Flow<SetupEffect> = effectChannel.receiveAsFlow()

    /** What setup shows. */
    val state: StateFlow<SetupUiState> = combine(form, permissions.status) { current, status ->
        current.copy(permissions = status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeout), SetupUiState())

    init {
        viewModelScope.launch {
            val server = settings.current().server
            form.update {
                it.copy(
                    address = server.address?.displayText().orEmpty(),
                    textAddress = server.textAddress?.displayText().orEmpty(),
                    advancedOpen = server.textAddress != null,
                    keySaved = server.key != null,
                    connection = if (server.isConfigured) ConnectionState.Saved else ConnectionState.Idle,
                )
            }
        }
    }

    /** Handles one user action. */
    fun onAction(action: SetupAction) {
        when (action) {
            is SetupAction.AddressChanged -> editServer { it.copy(address = action.value) }

            is SetupAction.TextAddressChanged -> editServer { it.copy(textAddress = action.value) }

            SetupAction.ToggleAdvanced -> form.update { it.copy(advancedOpen = !it.advancedOpen) }

            is SetupAction.KeyChanged -> editServer { it.copy(key = action.value) }

            SetupAction.Test -> test()

            SetupAction.ConfirmFingerprint -> confirm()

            SetupAction.RejectFingerprint -> {
                pending = null
                form.update { it.copy(connection = ConnectionState.Failed(R.string.setup_fingerprint_rejected)) }
            }

            is SetupAction.Grant -> viewModelScope.launch { fixLauncher.open(action.target) }

            SetupAction.Next -> next()

            SetupAction.Back -> form.update {
                it.copy(step = SetupStep.entries[(it.step.ordinal - 1).coerceAtLeast(0)])
            }

            SetupAction.Resumed -> viewModelScope.launch { permissions.refresh() }
        }
    }

    /** Any edit to the server form invalidates an earlier test. */
    private fun editServer(transform: (SetupUiState) -> SetupUiState) {
        pending = null
        form.update {
            transform(it).copy(
                formError = null,
                addressError = null,
                textAddressError = null,
                connection = ConnectionState.Idle,
            )
        }
    }

    private fun test() {
        val current = form.value
        val address = checkAddress(current.address)
        val text = current.textAddress.trim().takeIf { it.isNotEmpty() }?.let(::checkAddress)
        if (address !is AddressCheck.Valid || text is AddressCheck.Problem) {
            form.update {
                it.copy(
                    addressError = (address as? AddressCheck.Problem)?.message,
                    textAddressError = (text as? AddressCheck.Problem)?.message,
                )
            }
            return
        }
        val textEndpoint = (text as? AddressCheck.Valid)?.endpoint?.takeIf { it != address.endpoint }
        form.update {
            it.copy(
                address = address.endpoint.displayText(),
                textAddress = textEndpoint?.displayText().orEmpty(),
                connection = ConnectionState.Checking,
            )
        }
        viewModelScope.launch {
            val stored = settings.current().server
            val typed = current.key.trim()
            val both = setOf(ServerRole.SPEECH, ServerRole.TEXT)
            val test = PendingTest(
                targets = listOfNotNull(
                    ServerTestRun.Target(
                        address.endpoint,
                        if (textEndpoint == null) both else setOf(ServerRole.SPEECH),
                        stored.pinFor(address.endpoint),
                    ),
                    textEndpoint?.let { ServerTestRun.Target(it, setOf(ServerRole.TEXT), stored.pinFor(it)) },
                ),
                key = if (typed.isEmpty()) stored.key else ServerKey(typed),
            )
            pending = test
            runPending(test)
        }
    }

    private suspend fun runPending(test: PendingTest) {
        val passed = mutableListOf<ServerTestRun.Result.Passed>()
        var stop: ServerTestRun.Result? = null
        for (target in test.targets) {
            val result = runner.run(target, test.key, test.confirmed[target.endpoint])
            if (result is ServerTestRun.Result.Passed) {
                passed += result
            } else {
                stop = result
                test.awaiting = (result as? ServerTestRun.Result.Confirm)?.let { target.endpoint to it.fingerprint }
                break
            }
        }
        when (stop) {
            null, is ServerTestRun.Result.Passed -> save(test, passed)

            is ServerTestRun.Result.Confirm -> form.update {
                it.copy(
                    connection = ConnectionState.ConfirmFingerprint(
                        fingerprint = stop.fingerprint,
                        changed = stop.changed,
                        address = test.awaiting?.first?.displayText().orEmpty(),
                    ),
                )
            }

            is ServerTestRun.Result.Failed -> {
                pending = null
                form.update { it.copy(connection = ConnectionState.Failed(stop.error.setupMessageRes())) }
            }
        }
    }

    private fun confirm() {
        val test = pending ?: return
        val (endpoint, pin) = test.awaiting ?: return
        test.confirmed[endpoint] = pin
        test.awaiting = null
        form.update { it.copy(connection = ConnectionState.Checking) }
        viewModelScope.launch { runPending(test) }
    }

    private suspend fun save(test: PendingTest, passed: List<ServerTestRun.Result.Passed>) {
        val speechEndpoint = test.targets.first().endpoint
        val textEndpoint = test.targets.getOrNull(1)?.endpoint
        val speech = passed.first()
        val text = passed.getOrNull(1)
        val saved = settings.update { current ->
            val previous = current.server.models.takeIf {
                current.server.address == speechEndpoint && current.server.textAddress == textEndpoint
            } ?: ServerModels.Unset
            val server = ServerConfig(
                address = speechEndpoint,
                key = test.key,
                certificate = speech.pin,
                textAddress = textEndpoint,
                textCertificate = text?.pin,
                models = chooseModels(previous, speech.check, (text ?: speech).check),
            )
            current.copy(server = server)
        }
        pending = null
        val connection = when (saved) {
            is Outcome.Err -> ConnectionState.Failed(R.string.message_save_failed)

            is Outcome.Ok -> {
                health.probe()
                ConnectionState.Connected(connectedSummary(saved.value.server, speech.check, (text ?: speech).check))
            }
        }
        form.update { it.copy(key = "", keySaved = test.key != null, connection = connection) }
    }

    private fun next() {
        val current = state.value
        if (!current.canContinue) return
        if (current.step == SetupStep.entries.last()) {
            viewModelScope.launch { effectChannel.send(SetupEffect.Finished) }
        } else {
            form.update { it.copy(step = SetupStep.entries[current.step.ordinal + 1]) }
        }
    }

    /**
     * A test in progress: the addresses, the key, the certificates confirmed so far and the one waiting for the
     * user.
     */
    private class PendingTest(val targets: List<ServerTestRun.Target>, val key: ServerKey?) {
        val confirmed: MutableMap<ServerEndpoint, CertificateFingerprint> = mutableMapOf()
        var awaiting: Pair<ServerEndpoint, CertificateFingerprint>? = null
    }

    private companion object {
        /** The pin stored for [endpoint] when it is one of the stored addresses. */
        fun ServerConfig.pinFor(endpoint: ServerEndpoint): CertificateFingerprint? = when (endpoint) {
            address -> certificate
            textAddress -> textCertificate
            else -> null
        }
    }
}
