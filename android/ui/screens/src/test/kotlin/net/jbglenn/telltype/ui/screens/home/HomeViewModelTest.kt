package net.jbglenn.telltype.ui.screens.home

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.testing.FakeBubbleSnooze
import net.jbglenn.telltype.testing.FakeFixLauncher
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakePermissionStatusSource
import net.jbglenn.telltype.testing.FakeServerHealthSource
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.Fixtures
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

class HomeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val permissions = FakePermissionStatusSource()
    private val health = FakeServerHealthSource()
    private val settings = FakeSettingsRepository()
    private val history = FakeHistoryRepository()
    private val snooze = FakeBubbleSnooze()
    private val fixLauncher = FakeFixLauncher()
    private val time = FixedLocalTime()

    private fun viewModel() = HomeViewModel(permissions, health, settings, history, snooze, fixLauncher, time)

    @Test
    fun unconfiguredServerIsABlockingProblemThatOpensSetup() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        assertFalse(vm.state.value.loading)
        assertEquals(listOf(HomeProblem.SERVER_NOT_SET_UP), vm.state.value.problems)

        vm.onAction(HomeAction.Fix(HomeProblem.SERVER_NOT_SET_UP))

        assertEquals(listOf(HomeEffect.OpenSetup), effects)
    }

    @Test
    fun missingPermissionsAreListedAndFixOpensTheRightPlace() = runTest {
        configure()
        permissions.changeDevice(
            Fixtures.permissionsGranted().copy(microphoneGranted = false, accessibilityEnabled = false),
        )
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HomeAction.Resumed)

        val problems = vm.state.value.problems
        assertTrue(HomeProblem.MICROPHONE_DENIED in problems)
        assertTrue(HomeProblem.ACCESSIBILITY_OFF in problems)
        vm.onAction(HomeAction.Fix(HomeProblem.MICROPHONE_DENIED))
        assertEquals(listOf(FixTarget.MICROPHONE_PERMISSION), fixLauncher.openCall.calls)
    }

    @Test
    fun unreachableServerIsShownAndCheckProbesAgain() = runTest {
        configure()
        health.probeCall.returns(ServerReachability.Unreachable(ServerUnreachable, Fixtures.Epoch))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HomeAction.Resumed)
        assertTrue(HomeProblem.SERVER_UNREACHABLE in vm.state.value.problems)
        vm.onAction(HomeAction.Fix(HomeProblem.SERVER_UNREACHABLE))

        assertEquals(2, health.probeCall.calls.size)
        assertFalse(vm.state.value.checkingServer)
    }

    @Test
    fun snoozeLastsAnHourAndCanBeEnded() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HomeAction.Snooze)
        assertEquals(Fixtures.Epoch + 1.hours, vm.state.value.snoozedUntil)

        vm.onAction(HomeAction.EndSnooze)
        assertEquals(null, vm.state.value.snoozedUntil)
    }

    @Test
    fun bubbleSwitchSavesAndStorageFailureIsReported() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        vm.onAction(HomeAction.SetBubbleEnabled(false))
        assertFalse(vm.state.value.bubbleEnabled)

        settings.faults.failure = StorageFailed
        vm.onAction(HomeAction.SetBubbleEnabled(true))

        assertFalse(vm.state.value.bubbleEnabled)
        assertEquals(listOf(HomeEffect.ShowMessage(R.string.message_save_failed)), effects)
    }

    @Test
    fun recentDictationsAndTodaysWordsAreSummarised() = runTest {
        repeat(7) { index ->
            history.upsert(
                Fixtures.historyEntry(
                    id = "session-$index",
                    createdAt = Fixtures.Epoch - (index * 10).minutes,
                    rawTranscript = "one two three four five six",
                ),
            )
        }
        val vm = viewModel()
        keepCollecting(vm.state)

        assertEquals(5, vm.state.value.recent.size)
        assertEquals("two words of final text each", 14, vm.state.value.stats.wordsToday)
        assertEquals(120, vm.state.value.stats.typicalWordsPerMinute)
    }

    private suspend fun configure() {
        settings.update {
            it.copy(
                server = ServerConfig(
                    address = ServerEndpoint("192.0.2.10", 8170),
                    key = ServerKey("stored-key"),
                    certificate = CertificateFingerprint("a".repeat(64)),
                ),
            )
        }
    }
}
