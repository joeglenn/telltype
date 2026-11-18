package net.jbglenn.telltype.ui.screens.history

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.CleanupTimedOut
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.testing.FakeClipboard
import net.jbglenn.telltype.testing.FakeDictionaryRepository
import net.jbglenn.telltype.testing.FakeHistoryRepository
import net.jbglenn.telltype.testing.FakeMisrecognitionReporter
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FakeTextCleaner
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.TestDispatcherProvider
import net.jbglenn.telltype.ui.designsystem.R as DesignR
import net.jbglenn.telltype.ui.screens.FixedLocalTime
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

class HistoryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val history = FakeHistoryRepository()
    private val settings = FakeSettingsRepository()
    private val dictionary = FakeDictionaryRepository()
    private val cleaner = FakeTextCleaner()
    private val clipboard = FakeClipboard()
    private val reporter = FakeMisrecognitionReporter()

    private fun viewModel() = HistoryViewModel(
        history,
        settings,
        dictionary,
        cleaner,
        clipboard,
        reporter,
        HistoryEraser(history, reporter),
        HistoryDeletions(HistoryEraser(history, reporter), TestDispatcherProvider(main.dispatcher)),
        FixedLocalTime(),
    )

    private fun entry(id: String, minute: Int, archived: Boolean = false) =
        Fixtures.historyEntry(id = id, createdAt = Fixtures.Epoch + minute.minutes, archived = archived)

    private fun ids(vm: HistoryViewModel) = vm.state.value.entries?.map { it.id.value }

    @Test
    fun listsRecentEntriesAndSearchesAfterAPause() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a", finalText = "Lunch at noon."))
        history.upsert(Fixtures.historyEntry(id = "b", finalText = "Call the garage."))
        val vm = viewModel()
        keepCollecting(vm.state)
        assertEquals(2, vm.state.value.entries?.size)

        vm.onAction(HistoryAction.QueryChanged("garage"))
        testScheduler.advanceTimeBy(100.milliseconds)
        assertEquals("not searched yet", 2, vm.state.value.entries?.size)

        testScheduler.advanceTimeBy(200.milliseconds)
        assertEquals(listOf(SessionId("b")), vm.state.value.entries?.map { it.id })
    }

    @Test
    fun historyOffIsReported() = runTest {
        settings.update { it.copy(privacy = it.privacy.copy(historyRetention = Duration.ZERO)) }
        val vm = viewModel()
        keepCollecting(vm.state)

        assertTrue(vm.state.value.historyOff)
    }

    @Test
    fun opensAnEntryAndCopiesItsTexts() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        vm.onAction(HistoryAction.Select(SessionId("a")))
        assertEquals("Hello world.", vm.state.value.detail?.entry?.finalText)

        vm.onAction(HistoryAction.CopyFinal)
        vm.onAction(HistoryAction.CopyRaw)

        assertEquals(listOf("Hello world.", "hello world"), clipboard.copyCall.calls)
        assertEquals(2, effects.count { it == HistoryEffect.ShowMessage(R.string.history_copied) })
    }

    @Test
    fun missingOrUnreadableEntryShowsMissing() = runTest {
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.Select(SessionId("gone")))
        assertTrue(vm.state.value.detail?.missing == true)

        history.upsert(Fixtures.historyEntry(id = "a"))
        history.faults.failure = StorageFailed
        vm.onAction(HistoryAction.Select(SessionId("a")))
        assertTrue(vm.state.value.detail?.missing == true)
    }

    @Test
    fun rerunCleanupShowsTheNewTextOrTheError() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        vm.onAction(HistoryAction.Select(SessionId("a")))

        vm.onAction(HistoryAction.RerunCleanup)
        val done = vm.state.value.detail?.rerun as RerunState.Done
        assertEquals("hello world", cleaner.cleanCall.calls.single().transcript)
        vm.onAction(HistoryAction.CopyRerun)
        assertEquals(done.text, clipboard.content)

        cleaner.cleanCall.returns(Outcome.Err(CleanupTimedOut))
        vm.onAction(HistoryAction.RerunCleanup)
        assertEquals(RerunState.Failed(DesignR.string.error_cleanup_timeout), vm.state.value.detail?.rerun)
    }

    @Test
    fun reportWrongSendsOnceAndRecoversFromFailure() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        vm.onAction(HistoryAction.Select(SessionId("a")))

        reporter.reportCall.returns(Outcome.Err(ServerUnreachable))
        vm.onAction(HistoryAction.ReportWrong)
        assertEquals(ReportState.IDLE, vm.state.value.detail?.reportState)
        assertEquals(HistoryEffect.ShowMessage(DesignR.string.error_server_unreachable), effects.last())

        reporter.reportCall.returns(Outcome.Ok(Unit))
        vm.onAction(HistoryAction.ReportWrong)
        vm.onAction(HistoryAction.ReportWrong)
        assertEquals(ReportState.SENT, vm.state.value.detail?.reportState)
        assertEquals(2, reporter.reportCall.calls.size)
    }

    @Test
    fun deleteAsksFirstAndClosesTheDetail() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        vm.onAction(HistoryAction.Select(SessionId("a")))

        vm.onAction(HistoryAction.AskDelete)
        assertTrue(vm.state.value.detail?.confirmingDelete == true)
        vm.onAction(HistoryAction.ConfirmDelete)

        assertNull(vm.state.value.detail)
        assertTrue(history.entries.isEmpty())
        assertTrue(HistoryEffect.EntryDeleted in effects)
        assertEquals("its reports go too", listOf(SessionId("a")), reporter.forgetCall.calls)
    }

    @Test
    fun clearHistoryFromTheMenuAsksWithTheCountThenEmptiesHistoryAndReports() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        history.upsert(Fixtures.historyEntry(id = "b"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        vm.onAction(HistoryAction.Select(SessionId("a")))
        assertEquals(2, vm.state.value.total)

        vm.onAction(HistoryAction.OpenMenu)
        assertTrue(vm.state.value.menuOpen)
        vm.onAction(HistoryAction.AskClear)
        assertFalse("the menu closes behind the question", vm.state.value.menuOpen)
        assertTrue(vm.state.value.confirmingClear)
        assertEquals("nothing deleted before confirming", 2, history.entries.size)
        vm.onAction(HistoryAction.ConfirmClear)

        assertFalse(vm.state.value.confirmingClear)
        assertEquals(emptyList<Any>(), vm.state.value.entries)
        assertEquals(0, vm.state.value.total)
        assertNull("the open entry is gone too", vm.state.value.detail)
        assertEquals(1, reporter.forgetAllCall.calls.size)
        assertEquals(listOf(HistoryEffect.ShowMessage(R.string.history_cleared)), effects)
    }

    @Test
    fun cancellingClearOrClosingTheMenuDeletesNothing() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.OpenMenu)
        vm.onAction(HistoryAction.CloseMenu)
        assertFalse(vm.state.value.menuOpen)
        vm.onAction(HistoryAction.AskClear)
        vm.onAction(HistoryAction.CancelClear)

        assertFalse(vm.state.value.confirmingClear)
        assertEquals(1, vm.state.value.entries?.size)
        assertEquals(0, reporter.forgetAllCall.calls.size)
    }

    @Test
    fun clearFailureKeepsTheListAndSaysSo() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        history.faults.failure = StorageFailed

        vm.onAction(HistoryAction.AskClear)
        vm.onAction(HistoryAction.ConfirmClear)

        assertFalse(vm.state.value.confirmingClear)
        assertEquals(1, vm.state.value.entries?.size)
        assertEquals(0, reporter.forgetAllCall.calls.size)
        assertEquals(listOf(HistoryEffect.ShowMessage(R.string.history_clear_failed)), effects)
    }

    @Test
    fun aReportThatCannotBeRemovedFailsTheClear() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        reporter.forgetAllCall.returns(Outcome.Err(StorageFailed))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        vm.onAction(HistoryAction.AskClear)
        vm.onAction(HistoryAction.ConfirmClear)

        assertEquals(listOf(HistoryEffect.ShowMessage(R.string.history_clear_failed)), effects)
    }

    @Test
    fun deleteFailureKeepsTheEntry() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a"))
        val vm = viewModel()
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        vm.onAction(HistoryAction.Select(SessionId("a")))
        history.faults.failure = StorageFailed

        vm.onAction(HistoryAction.AskDelete)
        vm.onAction(HistoryAction.ConfirmDelete)

        assertFalse(vm.state.value.detail?.confirmingDelete == true)
        assertEquals(1, history.entries.size)
        assertEquals(listOf(HistoryEffect.ShowMessage(R.string.message_save_failed)), effects)
    }

    @Test
    fun stateTextHidesTheQuery() {
        assertFalse(HistoryUiState(query = "private words").toString().contains("private"))
        assertFalse(RerunState.Done("private words").toString().contains("private"))
    }

    @Test
    fun swipeLeftHidesTheEntryAndDeletesItWithItsReportsWhenTheUndoBarGoes() = runTest {
        history.upsert(entry("a", 1))
        history.upsert(entry("b", 2))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.Delete(SessionId("a")))
        assertEquals(listOf("b"), ids(vm))
        assertEquals(HistoryUndo(SessionId("a"), UndoKind.DELETED), vm.state.value.undo)
        assertEquals("nothing is deleted while Undo is offered", 2, history.entries.size)
        assertEquals(emptyList<SessionId>(), reporter.forgetCall.calls)

        testScheduler.advanceTimeBy(HistoryDeletions.UndoWindow + 1.milliseconds)

        assertNull(vm.state.value.undo)
        assertEquals(listOf(SessionId("b")), history.entries.map { it.id })
        assertEquals("its reports go when the deletion is final", listOf(SessionId("a")), reporter.forgetCall.calls)
        assertEquals(listOf("b"), ids(vm))
    }

    @Test
    fun undoPutsADeletedEntryBackWhereItWasAndNothingIsDeleted() = runTest {
        history.upsert(entry("a", 1))
        history.upsert(entry("b", 2))
        history.upsert(entry("c", 3))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.Delete(SessionId("b")))
        assertEquals(listOf("c", "a"), ids(vm))
        vm.onAction(HistoryAction.Undo)
        testScheduler.advanceTimeBy(HistoryDeletions.UndoWindow * 2)

        assertEquals(listOf("c", "b", "a"), ids(vm))
        assertNull(vm.state.value.undo)
        assertEquals(3, history.entries.size)
        assertEquals(emptyList<SessionId>(), reporter.forgetCall.calls)
    }

    @Test
    fun dismissingTheUndoBarOrSwipingAgainMakesTheDeletionFinalAtOnce() = runTest {
        history.upsert(entry("a", 1))
        history.upsert(entry("b", 2))
        history.upsert(entry("c", 3))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.Delete(SessionId("a")))
        vm.onAction(HistoryAction.UndoDismissed)
        assertEquals(listOf(SessionId("b"), SessionId("c")), history.entries.map { it.id })

        vm.onAction(HistoryAction.Delete(SessionId("b")))
        vm.onAction(HistoryAction.Archive(SessionId("c")))
        assertEquals("the second swipe ends the first one's Undo", listOf("c"), history.entries.map { it.id.value })
        assertEquals(HistoryUndo(SessionId("c"), UndoKind.ARCHIVED), vm.state.value.undo)
        vm.onAction(HistoryAction.Undo)
        assertEquals("Undo applies to the last swipe only", false, history.entries.single().archived)
    }

    @Test
    fun swipeRightArchivesTheArchiveListsItAndSwipingBackOrUndoReturnsIt() = runTest {
        history.upsert(entry("a", 1))
        history.upsert(entry("b", 2))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.Archive(SessionId("a")))
        assertEquals(listOf("b"), ids(vm))
        assertEquals(1, vm.state.value.archivedCount)
        assertEquals(HistoryUndo(SessionId("a"), UndoKind.ARCHIVED), vm.state.value.undo)
        testScheduler.advanceTimeBy(HistoryDeletions.UndoWindow + 1.milliseconds)
        assertNull(vm.state.value.undo)

        vm.onAction(HistoryAction.OpenArchive)
        assertTrue(vm.state.value.archive)
        assertEquals(listOf("a"), ids(vm))

        vm.onAction(HistoryAction.Unarchive(SessionId("a")))
        assertEquals(emptyList<String>(), ids(vm))
        vm.onAction(HistoryAction.Undo)
        assertEquals(listOf("a"), ids(vm))

        vm.onAction(HistoryAction.Delete(SessionId("a")))
        testScheduler.advanceTimeBy(HistoryDeletions.UndoWindow + 1.milliseconds)
        assertEquals("swipe left in the Archive deletes", listOf(SessionId("b")), history.entries.map { it.id })

        vm.onAction(HistoryAction.CloseArchive)
        assertFalse(vm.state.value.archive)
        assertEquals(listOf("b"), ids(vm))
    }

    @Test
    fun searchCoversTheListOnScreen() = runTest {
        history.upsert(Fixtures.historyEntry(id = "a", finalText = "Garage at nine."))
        history.upsert(Fixtures.historyEntry(id = "b", finalText = "Call the garage.", archived = true))
        val vm = viewModel()
        keepCollecting(vm.state)

        vm.onAction(HistoryAction.QueryChanged("garage"))
        testScheduler.advanceTimeBy(300.milliseconds)
        assertEquals(listOf("a"), ids(vm))

        vm.onAction(HistoryAction.OpenArchive)
        assertEquals("opening the Archive starts a new search", "", vm.state.value.query)
        vm.onAction(HistoryAction.QueryChanged("garage"))
        testScheduler.advanceTimeBy(300.milliseconds)
        assertEquals(listOf("b"), ids(vm))
    }

    @Test
    fun theDetailShowsWhetherAnEntryIsArchivedAndTogglesIt() = runTest {
        history.upsert(entry("a", 1))
        val vm = viewModel()
        keepCollecting(vm.state)
        vm.onAction(HistoryAction.Select(SessionId("a")))

        vm.onAction(HistoryAction.Archive(SessionId("a")))
        assertEquals(true, vm.state.value.detail?.entry?.archived)
        assertEquals(true, history.entries.single().archived)

        vm.onAction(HistoryAction.Unarchive(SessionId("a")))
        assertEquals(false, vm.state.value.detail?.entry?.archived)
    }

    @Test
    fun clearHistoryKeepsTheArchiveUnlessTickedAndKeepsItsReports() = runTest {
        history.upsert(entry("a", 1))
        history.upsert(entry("b", 2, archived = true))
        val vm = viewModel()
        keepCollecting(vm.state)
        assertEquals(1, vm.state.value.historyCount)
        assertEquals(1, vm.state.value.archivedCount)

        vm.onAction(HistoryAction.AskClear)
        assertFalse(vm.state.value.clearIncludesArchive)
        vm.onAction(HistoryAction.ConfirmClear)
        assertEquals(listOf(SessionId("b")), history.entries.map { it.id })
        assertEquals(listOf(SessionId("a")), reporter.forgetCall.calls)
        assertEquals(0, reporter.forgetAllCall.calls.size)

        vm.onAction(HistoryAction.AskClear)
        vm.onAction(HistoryAction.ClearIncludesArchive(true))
        assertTrue(vm.state.value.clearIncludesArchive)
        vm.onAction(HistoryAction.ConfirmClear)
        assertTrue(history.entries.isEmpty())
        assertEquals(1, reporter.forgetAllCall.calls.size)
    }

    @Test
    fun theArchiveStaysReachableWhileHistoryIsOff() = runTest {
        settings.update { it.copy(privacy = it.privacy.copy(saveHistory = false)) }
        history.upsert(entry("a", 1, archived = true))
        val vm = viewModel()
        keepCollecting(vm.state)
        assertTrue(vm.state.value.historyOff)
        assertEquals(1, vm.state.value.archivedCount)

        vm.onAction(HistoryAction.OpenArchive)
        assertEquals(listOf("a"), ids(vm))
    }
}
