package net.jbglenn.telltype.ui.screens.snippets

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.testing.FakeSnippetRepository
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

class SnippetsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeSnippetRepository()

    @Test
    fun startsLoadingThenShowsTheList() = runTest {
        val vm = SnippetsViewModel(repository)
        assertTrue(vm.state.value.loading)

        keepCollecting(vm.state)

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.snippets.isEmpty())
    }

    @Test
    fun addsAndEditsASnippet() = runTest {
        val vm = SnippetsViewModel(repository)
        keepCollecting(vm.state)

        vm.onAction(SnippetsAction.Add)
        vm.onAction(SnippetsAction.TriggerChanged(" my address "))
        vm.onAction(SnippetsAction.TextChanged("1 Example Street"))
        vm.onAction(SnippetsAction.Save)
        val saved = repository.snippets.first().single()
        assertEquals("my address", saved.trigger)

        vm.onAction(SnippetsAction.Edit(saved.id))
        vm.onAction(SnippetsAction.TextChanged("2 Example Street"))
        vm.onAction(SnippetsAction.Save)

        assertEquals("2 Example Street", repository.snippets.first().single().text)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun blankAndDuplicateTriggersAreRefused() = runTest {
        repository.add("my address", "1 Example Street")
        val vm = SnippetsViewModel(repository)
        keepCollecting(vm.state)

        vm.onAction(SnippetsAction.Add)
        vm.onAction(SnippetsAction.Save)
        assertEquals(R.string.snippets_error_trigger_blank, vm.state.value.editor?.error)

        vm.onAction(SnippetsAction.TriggerChanged("My Address"))
        vm.onAction(SnippetsAction.TextChanged("elsewhere"))
        vm.onAction(SnippetsAction.Save)
        assertEquals(R.string.snippets_error_duplicate, vm.state.value.editor?.error)
    }

    @Test
    fun deleteAsksFirstAndReportsStorageFailure() = runTest {
        val snippet = (repository.add("sig", "Best regards") as Outcome.Ok).value
        val vm = SnippetsViewModel(repository)
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)

        vm.onAction(SnippetsAction.AskDelete(snippet.id))
        assertEquals(snippet, vm.state.value.pendingDelete)
        repository.faults.failure = StorageFailed
        vm.onAction(SnippetsAction.ConfirmDelete)

        assertEquals(listOf(SnippetsEffect.ShowMessage(R.string.message_save_failed)), effects)
        repository.faults.failure = null
        assertEquals(1, repository.snippets.first().size)
    }

    @Test
    fun editorTextIsNotInItsDescription() {
        val editor = SnippetEditor(editing = null, trigger = "secret", text = "secret text")

        assertFalse(editor.toString().contains("secret"))
    }
}
