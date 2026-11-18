package net.jbglenn.telltype.ui.screens.settings

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.StorageFull
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.PrivacySettings
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakeMisrecognitionReporter
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.ui.screens.FixedLocalTime
import net.jbglenn.telltype.ui.screens.MainDispatcherRule
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.collectInto
import net.jbglenn.telltype.ui.screens.history.HistoryEraser
import net.jbglenn.telltype.ui.screens.keepCollecting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeSettingsRepository()
    private val history = FakeHistoryRepository()
    private val reporter = FakeMisrecognitionReporter()

    private fun viewModel() = SettingsViewModel(repository, HistoryEraser(history, reporter), FixedLocalTime())

    @Test
    fun eachRowChangesItsSetting() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SettingsAction.BubbleSideChanged(BubbleSide.LEFT))
        vm.onAction(SettingsAction.CleanupEnabled(false))
        vm.onAction(SettingsAction.HistoryRetention(7.days))
        vm.onAction(SettingsAction.NeverUseClipboard(true))

        val settings = repository.current()
        assertEquals(BubbleSide.LEFT, settings.bubble.side)
        assertFalse(settings.cleanup.enabled)
        assertEquals(7.days, settings.privacy.historyRetention)
        assertEquals(true, settings.insertion.neverUseClipboard)
    }

    @Test
    fun verboseLoggingSwitchesItselfOffAfterAnHour() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SettingsAction.VerboseLogging(true))
        assertEquals(Fixtures.Epoch + 1.hours, repository.current().advanced.verboseLoggingUntil)

        vm.onAction(SettingsAction.VerboseLogging(false))
        assertNull(repository.current().advanced.verboseLoggingUntil)
    }

    @Test
    fun storageFailureLeavesTheSettingAndSaysSo() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        repository.faults.failure = StorageFull

        vm.onAction(SettingsAction.Haptics(false))

        assertEquals(true, repository.current().bubble.haptics)
        assertEquals(listOf(SettingsEffect.ShowMessage(R.string.message_save_failed)), effects)
    }

    @Test
    fun clearHistoryNowAsksWithTheCountThenDeletesEntriesAndReports() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        history.upsert(Fixtures.historyEntry(id = "b"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        assertEquals(2, vm.state.value.historyCount)

        vm.onAction(SettingsAction.AskClearHistory)
        assertTrue(vm.state.value.confirmingClear)
        assertEquals("nothing deleted before confirming", 2, history.entries.size)
        vm.onAction(SettingsAction.ConfirmClearHistory)

        assertFalse(vm.state.value.confirmingClear)
        assertEquals(0, vm.state.value.historyCount)
        assertEquals(1, reporter.forgetAllCall.calls.size)
        assertEquals(listOf(SettingsEffect.ShowMessage(R.string.history_cleared)), effects)
    }

    @Test
    fun cancellingClearHistoryKeepsEverything() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SettingsAction.AskClearHistory)
        vm.onAction(SettingsAction.CancelClearHistory)

        assertFalse(vm.state.value.confirmingClear)
        assertEquals(1, history.entries.size)
        assertEquals(0, reporter.forgetAllCall.calls.size)
    }

    @Test
    fun aFailedClearKeepsHistoryAndSaysSo() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        history.faults.failure = StorageFull

        vm.onAction(SettingsAction.AskClearHistory)
        vm.onAction(SettingsAction.ConfirmClearHistory)

        assertEquals(1, history.entries.size)
        assertEquals(0, reporter.forgetAllCall.calls.size)
        assertEquals(listOf(SettingsEffect.ShowMessage(R.string.history_clear_failed)), effects)
    }

    @Test
    fun saveHistoryOffWithNothingSavedSwitchesOffAtOnceAndOnAgain() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SettingsAction.SaveHistory(false))
        assertFalse(vm.state.value.confirmingHistoryOff)
        assertFalse(repository.current().privacy.historyOn)

        vm.onAction(SettingsAction.SaveHistory(true))
        assertTrue(repository.current().privacy.historyOn)
        assertEquals(30.days, repository.current().privacy.historyRetention)
    }

    @Test
    fun saveHistoryOffAsksAndDeletesOnlyWhatIsTicked() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        history.upsert(Fixtures.historyEntry(id = "b", archived = true))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SettingsAction.SaveHistory(false))
        assertTrue(vm.state.value.confirmingHistoryOff)
        assertTrue("still on while asking", repository.current().privacy.historyOn)
        assertEquals(1, vm.state.value.unarchivedCount)
        assertEquals(1, vm.state.value.archivedCount)
        vm.onAction(SettingsAction.CancelHistoryOff)
        assertTrue(repository.current().privacy.historyOn)

        vm.onAction(SettingsAction.SaveHistory(false))
        vm.onAction(SettingsAction.ConfirmHistoryOff)
        assertFalse(repository.current().privacy.historyOn)
        assertEquals("nothing ticked, nothing deleted", 2, history.entries.size)

        vm.onAction(SettingsAction.SaveHistory(true))
        vm.onAction(SettingsAction.SaveHistory(false))
        vm.onAction(SettingsAction.HistoryOffDeletesSaved(true))
        vm.onAction(SettingsAction.ConfirmHistoryOff)
        assertEquals(listOf(SessionId("b")), history.entries.map { it.id })

        vm.onAction(SettingsAction.SaveHistory(true))
        vm.onAction(SettingsAction.SaveHistory(false))
        vm.onAction(SettingsAction.HistoryOffDeletesArchive(true))
        vm.onAction(SettingsAction.ConfirmHistoryOff)
        assertTrue(history.entries.isEmpty())
        assertFalse(repository.current().privacy.historyOn)
    }

    @Test
    fun anOldRetentionOfZeroReadsAsOffAndSwitchingOnRestoresTheDefault() = runTest {
        repository.update { it.copy(privacy = PrivacySettings(historyRetention = Duration.ZERO)) }
        val vm = viewModel()
        keepCollecting(vm.state)
        assertFalse(vm.state.value.settings?.privacy?.historyOn == true)

        vm.onAction(SettingsAction.SaveHistory(true))
        assertEquals(PrivacySettings(historyRetention = 30.days, saveHistory = true), repository.current().privacy)
    }

    @Test
    fun clearHistoryNowKeepsTheArchiveUnlessTicked() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        history.upsert(Fixtures.historyEntry(id = "b", archived = true))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(SettingsAction.AskClearHistory)
        vm.onAction(SettingsAction.ConfirmClearHistory)
        assertEquals(listOf(SessionId("b")), history.entries.map { it.id })

        vm.onAction(SettingsAction.AskClearHistory)
        vm.onAction(SettingsAction.ClearIncludesArchive(true))
        vm.onAction(SettingsAction.ConfirmClearHistory)
        assertTrue(history.entries.isEmpty())
    }
}
