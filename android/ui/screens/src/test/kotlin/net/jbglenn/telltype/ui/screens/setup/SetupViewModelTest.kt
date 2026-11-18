package net.jbglenn.telltype.ui.screens.setup

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.CertificateMismatch
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.RequestRejected
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.OfferedModel
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerCandidate
import net.jbglenn.telltype.core.model.settings.ServerCheck
import net.jbglenn.telltype.core.model.settings.ServerCheckMethod
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.testing.FakeFixLauncher
import net.jbglenn.telltype.testing.FakePermissionStatusSource
import net.jbglenn.telltype.testing.FakeServerCertificateReader
import net.jbglenn.telltype.testing.FakeServerConnectionChecker
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.designsystem.R as DesignR
import net.jbglenn.telltype.ui.screens.MainDispatcherRule
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.collectInto
import net.jbglenn.telltype.ui.screens.keepCollecting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val certificates = FakeServerCertificateReader()
    private val checker = FakeServerConnectionChecker()
    private val health = FakeServerHealthSource()
    private val permissions = FakePermissionStatusSource()
    private val fixLauncher = FakeFixLauncher()

    private fun viewModel() = SetupViewModel(settings, certificates, checker, health, permissions, fixLauncher)

    @Test
    fun testWithoutAddressAsksForOne() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SetupAction.Test)

        assertEquals(R.string.setup_error_no_address, vm.state.value.addressError)
        assertTrue(checker.checkCall.calls.isEmpty())
    }

    @Test
    fun plainHttpToAPublicAddressIsRefusedBeforeAnyNetworkCall() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SetupAction.AddressChanged("http://203.0.113.5:8000"))
        vm.onAction(SetupAction.Test)
        assertEquals(R.string.setup_error_address_http, vm.state.value.addressError)
        vm.onAction(SetupAction.ToggleAdvanced)
        vm.onAction(SetupAction.AddressChanged("192.0.2.10"))
        vm.onAction(SetupAction.TextAddressChanged("http://server.example:8080"))
        assertNull("editing clears the message", vm.state.value.addressError)
        vm.onAction(SetupAction.Test)
        assertEquals(R.string.setup_error_address_http, vm.state.value.textAddressError)
        assertTrue(checker.checkCall.calls.isEmpty())
    }

    @Test
    fun aSelfSignedCertificateWaitsForConfirmationThenSavesAndConnects() = runTest {
        selfSignedGate()
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SetupAction.AddressChanged("192.0.2.10"))
        vm.onAction(SetupAction.KeyChanged("secret-key"))
        vm.onAction(SetupAction.Test)

        val confirm = vm.state.value.connection as ConnectionState.ConfirmFingerprint
        assertEquals(FakeServerCertificateReader.DefaultFingerprint, confirm.fingerprint)
        assertFalse(confirm.changed)
        assertEquals("192.0.2.10:8170", confirm.address)
        assertFalse("nothing is saved before confirmation", settings.current().server.isConfigured)

        vm.onAction(SetupAction.ConfirmFingerprint)

        val server = settings.current().server
        assertEquals(ServerEndpoint("192.0.2.10", 8170), server.address)
        assertEquals(ServerKey("secret-key"), server.key)
        assertEquals(FakeServerCertificateReader.DefaultFingerprint, server.certificate)
        assertEquals(
            "real names: Gemma 4 E4B for cleanup, Qwen3.5 4B for commands",
            ServerModels(speech = null, cleanup = "gemma-4-e4b-it-qat-q4_0", command = "qwen_qwen3.5-4b-q4_k_m"),
            server.models,
        )
        val summary = (vm.state.value.connection as ConnectionState.Connected).summary
        assertEquals(ServerCheckMethod.GATE_HEALTH, summary.speechCheck)
        assertNull(summary.textAddress)
        assertEquals("whisper-1", summary.speechModel)
        assertEquals(
            listOf(null, FakeServerCertificateReader.DefaultFingerprint),
            checker.checkCall.calls.map {
                it.certificate
            },
        )
        assertEquals(1, health.probeCall.calls.size)
        assertEquals("", vm.state.value.key)
        assertTrue(vm.state.value.canContinue)
    }

    @Test
    fun twoPlainHttpStockServersWithoutAKeyPickTheRecommendedModel() = runTest {
        checker.checkCall.answers { candidate ->
            if (ServerRole.TEXT in candidate.roles) {
                passed(
                    candidate,
                    ServerCheckMethod.MODELS,
                    listOf(OfferedModel("Qwen_Qwen3.5-2B-Q4_K_M.gguf"), OfferedModel("qwen3.5-9b")),
                )
            } else {
                passed(candidate, ServerCheckMethod.SILENT_TRANSCRIPTION, null)
            }
        }
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SetupAction.AddressChanged("http://192.168.1.20:18201"))
        vm.onAction(SetupAction.ToggleAdvanced)
        vm.onAction(SetupAction.TextAddressChanged("http://192.168.1.20:18200/v1"))
        vm.onAction(SetupAction.Test)

        val server = settings.current().server
        assertEquals(ServerEndpoint("192.168.1.20", 18201, Scheme.HTTP), server.address)
        assertEquals(ServerEndpoint("192.168.1.20", 18200, Scheme.HTTP), server.textAddress)
        assertNull(server.key)
        assertNull(server.certificate)
        assertEquals(
            "no Gemma 4 E4B: cleanup takes the first listed; commands the first recommended",
            ServerModels(speech = null, cleanup = "Qwen_Qwen3.5-2B-Q4_K_M.gguf", command = "qwen3.5-9b"),
            server.models,
        )
        assertTrue("HTTP has no certificate to read", certificates.readCall.calls.isEmpty())
        val summary = (vm.state.value.connection as ConnectionState.Connected).summary
        assertEquals(ServerCheckMethod.SILENT_TRANSCRIPTION, summary.speechCheck)
        assertEquals("http://192.168.1.20:18200", summary.textAddress)
        assertEquals(ServerCheckMethod.MODELS, summary.textCheck)
        assertEquals(listOf(setOf(ServerRole.SPEECH), setOf(ServerRole.TEXT)), checker.checkCall.calls.map { it.roles })
        assertFalse(vm.state.value.keySaved)
    }

    @Test
    fun aTextAddressWithoutAModelListSaysSo() = runTest {
        checker.checkCall.returns(Outcome.Err(RequestRejected(404, "models_missing")))
        val vm = viewModel()
        keepCollecting(vm.state)
        vm.onAction(SetupAction.AddressChanged("http://192.168.1.20:18201"))

        vm.onAction(SetupAction.Test)

        assertEquals(ConnectionState.Failed(R.string.setup_error_no_models), vm.state.value.connection)
        assertFalse(settings.current().server.isConfigured)
    }

    @Test
    fun changedCertificateIsFlaggedAndRejectingKeepsTheOldOne() = runTest {
        val old = CertificateFingerprint("f".repeat(64))
        settings.update { it.copy(server = configured(old)) }
        selfSignedGate()
        checker.checkCall.answers { candidate ->
            if (candidate.certificate == FakeServerCertificateReader.DefaultFingerprint) {
                passed(candidate, ServerCheckMethod.MODELS, null)
            } else {
                Outcome.Err(CertificateMismatch)
            }
        }
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SetupAction.Test)
        val confirm = vm.state.value.connection as ConnectionState.ConfirmFingerprint
        assertTrue(confirm.changed)

        vm.onAction(SetupAction.RejectFingerprint)

        assertEquals(ConnectionState.Failed(R.string.setup_fingerprint_rejected), vm.state.value.connection)
        assertEquals(old, settings.current().server.certificate)
    }

    @Test
    fun knownCertificateConnectsWithoutAskingAgain() = runTest {
        settings.update { it.copy(server = configured(FakeServerCertificateReader.DefaultFingerprint)) }
        selfSignedGate()
        val vm = viewModel()
        keepCollecting(vm.state)
        assertEquals(ConnectionState.Saved, vm.state.value.connection)
        assertEquals("192.0.2.10:8170", vm.state.value.address)

        vm.onAction(SetupAction.Test)

        assertTrue(vm.state.value.connection is ConnectionState.Connected)
        assertTrue(certificates.readCall.calls.isEmpty())
        assertEquals("the saved key is kept", ServerKey("stored-key"), settings.current().server.key)
    }

    @Test
    fun unreachableServerShowsTheErrorWithoutGuessingTheNetwork() = runTest {
        checker.checkCall.returns(Outcome.Err(ServerUnreachable))
        val vm = viewModel()
        keepCollecting(vm.state)
        vm.onAction(SetupAction.AddressChanged("192.0.2.10"))

        vm.onAction(SetupAction.Test)

        assertEquals(listOf(ServerEndpoint("192.0.2.10", 8170)), checker.checkCall.calls.map { it.endpoint })
        assertEquals(ConnectionState.Failed(DesignR.string.connection_unreachable), vm.state.value.connection)
        assertFalse(vm.state.value.canContinue)
    }

    @Test
    fun storageFailureWhileSavingIsReported() = runTest {
        selfSignedGate()
        val vm = viewModel()
        keepCollecting(vm.state)
        vm.onAction(SetupAction.AddressChanged("server.example"))
        vm.onAction(SetupAction.Test)
        settings.faults.failure = StorageFailed

        vm.onAction(SetupAction.ConfirmFingerprint)

        assertEquals(ConnectionState.Failed(R.string.message_save_failed), vm.state.value.connection)
    }

    @Test
    fun checkingStateLastsUntilTheCheckAnswers() = runTest {
        checker.checkCall.delay = 500.milliseconds
        val vm = viewModel()
        keepCollecting(vm.state)
        vm.onAction(SetupAction.AddressChanged("server.example"))

        vm.onAction(SetupAction.Test)
        assertEquals(ConnectionState.Checking, vm.state.value.connection)

        testScheduler.advanceTimeBy(501.milliseconds)
        assertTrue(vm.state.value.connection is ConnectionState.Connected)
    }

    @Test
    fun microphoneStepNeedsThePermissionAndResumeRereadsIt() = runTest {
        settings.update { it.copy(server = configured(FakeServerCertificateReader.DefaultFingerprint)) }
        permissions.changeDevice(Fixtures.permissionsGranted().copy(microphoneGranted = false))
        permissions.refresh()
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SetupAction.Next)
        assertEquals(SetupStep.MICROPHONE, vm.state.value.step)
        vm.onAction(SetupAction.Next)
        assertEquals("cannot pass without the microphone", SetupStep.MICROPHONE, vm.state.value.step)

        vm.onAction(SetupAction.Grant(FixTarget.MICROPHONE_PERMISSION))
        assertEquals(listOf(FixTarget.MICROPHONE_PERMISSION), fixLauncher.openCall.calls)
        permissions.changeDevice(Fixtures.permissionsGranted())
        val before = permissions.refreshCount
        vm.onAction(SetupAction.Resumed)

        assertEquals(before + 1, permissions.refreshCount)
        assertTrue(vm.state.value.canContinue)
    }

    @Test
    fun lastStepFinishesSetupAndBackReturns() = runTest {
        settings.update { it.copy(server = configured(FakeServerCertificateReader.DefaultFingerprint)) }
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        repeat(SetupStep.entries.size - 1) { vm.onAction(SetupAction.Next) }
        assertEquals(SetupStep.PRACTICE, vm.state.value.step)
        vm.onAction(SetupAction.Back)
        assertEquals(SetupStep.ACCESSIBILITY, vm.state.value.step)
        vm.onAction(SetupAction.Next)
        vm.onAction(SetupAction.Next)

        assertEquals(listOf(SetupEffect.Finished), effects)
    }

    @Test
    fun keyNeverAppearsInStateText() {
        val state = SetupUiState(key = "secret-key")

        assertFalse(state.toString().contains("secret-key"))
        assertFalse(SetupAction.KeyChanged("secret-key").toString().contains("secret-key"))
    }

    private fun configured(certificate: CertificateFingerprint) = ServerConfig(
        address = ServerEndpoint("192.0.2.10", 8170),
        key = ServerKey("stored-key"),
        certificate = certificate,
    )

    private fun passed(candidate: ServerCandidate, method: ServerCheckMethod, models: List<OfferedModel>?) =
        Outcome.Ok(ServerCheck(candidate.endpoint, candidate.roles.associateWith { method }, models, health = null))

    /** Our gate: self-signed, so the system's trust refuses it until its fingerprint is pinned. */
    private fun selfSignedGate() = checker.checkCall.answers { candidate ->
        if (candidate.certificate == null) {
            Outcome.Err(CertificateMismatch)
        } else {
            passed(
                candidate,
                ServerCheckMethod.GATE_HEALTH,
                listOf(OfferedModel("qwen_qwen3.5-4b-q4_k_m"), OfferedModel("gemma-4-e4b-it-qat-q4_0")),
            )
        }
    }

    @Test
    fun theAddressIsCheckedWithAMessageForEachProblem() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)
        val rows = listOf(
            "   " to R.string.setup_error_no_address,
            "192.0.2.10 :8170" to R.string.setup_error_address_spaces,
            "192.0.2.10:99999" to R.string.setup_error_address_port,
            "192.0.2.10:port" to R.string.setup_error_address_port,
            "http://server.example" to R.string.setup_error_address_http,
            "my_server!" to R.string.setup_error_address,
        )
        for ((text, message) in rows) {
            vm.onAction(SetupAction.AddressChanged(text))
            vm.onAction(SetupAction.Test)
            assertEquals(text, message, vm.state.value.addressError)
        }
        assertTrue("nothing was contacted", checker.checkCall.calls.isEmpty())

        vm.onAction(SetupAction.AddressChanged("https://myserver.local/"))
        assertEquals("typing clears the message", null, vm.state.value.addressError)
        vm.onAction(SetupAction.Test)
        assertEquals(null, vm.state.value.addressError)
        assertEquals("https:// without a port is 443, as in a URL", "myserver.local:443", vm.state.value.address)
        assertEquals(listOf(ServerEndpoint("myserver.local", 443)), checker.checkCall.calls.map { it.endpoint })
    }
}
