package net.jbglenn.telltype.ui.screens.diagnostics

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.domain.port.SelfTestReport
import net.jbglenn.telltype.testing.FakeDebugBundleExporter
import net.jbglenn.telltype.testing.FakeFixLauncher
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakePermissionStatusSource
import net.jbglenn.telltype.testing.FakeSelfTest
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.designsystem.R as DesignR
import net.jbglenn.telltype.ui.screens.FixedLocalTime
import net.jbglenn.telltype.ui.screens.MainDispatcherRule
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.collectInto
import net.jbglenn.telltype.ui.screens.keepCollecting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiagnosticsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val permissions = FakePermissionStatusSource()
    private val health = FakeServerHealthSource()
    private val history = FakeHistoryRepository()
    private val fixLauncher = FakeFixLauncher()
    private val selfTest = FakeSelfTest()
    private val exporter = FakeDebugBundleExporter()

    private fun viewModel() = DiagnosticsViewModel(
        permissions,
        health,
        history,
        fixLauncher,
        selfTest,
        exporter,
        FixedLocalTime(),
        FakeSettingsRepository(),
    )

    @Test
    fun checkNowShowsUnreachableServer() = runTest {
        health.probeCall.returns(ServerReachability.Unreachable(ServerUnreachable, Fixtures.Epoch))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(DiagnosticsAction.CheckServer)

        assertTrue(vm.state.value.reachability is ServerReachability.Unreachable)
        assertFalse(vm.state.value.checkingServer)
    }

    @Test
    fun checkNowIgnoresARepeatWhileRunning() = runTest {
        health.probeCall.delay = 2.seconds
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(DiagnosticsAction.CheckServer)
        vm.onAction(DiagnosticsAction.CheckServer)
        assertTrue(vm.state.value.checkingServer)
        testScheduler.advanceTimeBy(3.seconds)

        assertEquals(1, health.probeCall.calls.size)
        assertFalse(vm.state.value.checkingServer)
    }

    @Test
    fun selfTestReportsResultOrError() = runTest {
        val report = SelfTestReport(passed = true, timings = SessionTimings.None)
        selfTest.runCall.returns(Outcome.Ok(report))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(DiagnosticsAction.RunSelfTest)
        assertEquals(SelfTestState.Finished(report), vm.state.value.selfTest)

        selfTest.runCall.returns(Outcome.Err(ServerUnreachable))
        vm.onAction(DiagnosticsAction.RunSelfTest)
        assertEquals(SelfTestState.Failed(DesignR.string.connection_unreachable), vm.state.value.selfTest)
    }

    @Test
    fun exportFailureIsReported() = runTest {
        exporter.exportCall.returns(Outcome.Err(StorageFull))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        vm.onAction(DiagnosticsAction.Export)

        assertEquals(listOf(DiagnosticsEffect.ShowMessage(R.string.diagnostics_export_failed)), effects)
        assertFalse(vm.state.value.exporting)
    }

    @Test
    fun resumeRereadsPermissionsAndFixOpensSettings() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)
        permissions.changeDevice(Fixtures.permissionsGranted().copy(notificationsGranted = false))

        vm.onAction(DiagnosticsAction.Resumed)
        vm.onAction(DiagnosticsAction.Fix(FixTarget.NOTIFICATION_PERMISSION))

        assertEquals(false, vm.state.value.permissions?.notificationsGranted)
        assertEquals(listOf(FixTarget.NOTIFICATION_PERMISSION), fixLauncher.openCall.calls)
    }

    @Test
    fun showsTheLastSessions() = runTest {
        repeat(3) { history.upsert(Fixtures.historyEntry(id = "s$it")) }
        val vm = viewModel()
        keepCollecting(vm.state)

        assertEquals(3, vm.state.value.sessions.size)
    }
}
