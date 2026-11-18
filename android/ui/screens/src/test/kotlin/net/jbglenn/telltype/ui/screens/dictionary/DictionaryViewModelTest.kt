package net.jbglenn.telltype.ui.screens.dictionary

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.testing.FakeDictionaryRepository
import net.jbglenn.telltype.ui.screens.MainDispatcherRule
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.collectInto
import net.jbglenn.telltype.ui.screens.keepCollecting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DictionaryViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val repository = FakeDictionaryRepository()

    @Test
    fun addsAWordWithSoundsLikeForms() = runTest {
        val vm = DictionaryViewModel(repository)
        keepCollecting(vm.state)

        vm.onAction(DictionaryAction.Add)
        vm.onAction(DictionaryAction.FirstFieldChanged("Oslo"))
        vm.onAction(DictionaryAction.SecondFieldChanged("oz lo, ozlo"))
        vm.onAction(DictionaryAction.Save)

        val term = repository.terms.first().single()
        assertEquals("Oslo", term.term)
        assertEquals(listOf("oz lo", "ozlo"), term.soundsLike)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun blankAndDuplicateWordsAreRefused() = runTest {
        repository.addTerm("Oslo", emptyList(), EntrySource.MANUAL)
        val vm = DictionaryViewModel(repository)
        keepCollecting(vm.state)

        vm.onAction(DictionaryAction.Add)
        vm.onAction(DictionaryAction.Save)
        assertEquals(R.string.dictionary_error_word_blank, vm.state.value.editor?.error)

        vm.onAction(DictionaryAction.FirstFieldChanged("oslo"))
        vm.onAction(DictionaryAction.Save)
        assertEquals(R.string.dictionary_error_word_duplicate, vm.state.value.editor?.error)
        assertEquals(1, repository.terms.first().size)
    }

    @Test
    fun correctionsNeedTwoDifferentForms() = runTest {
        val vm = DictionaryViewModel(repository)
        keepCollecting(vm.state)
        vm.onAction(DictionaryAction.SelectTab(DictionaryTab.CORRECTIONS))

        vm.onAction(DictionaryAction.Add)
        vm.onAction(DictionaryAction.FirstFieldChanged("cube"))
        vm.onAction(DictionaryAction.SecondFieldChanged("cube"))
        vm.onAction(DictionaryAction.Save)
        assertEquals(R.string.dictionary_error_pair_same, vm.state.value.editor?.error)

        vm.onAction(DictionaryAction.SecondFieldChanged("kube"))
        vm.onAction(DictionaryAction.Save)
        assertEquals("kube", repository.replacements.first().single().replaceWith)
    }

    @Test
    fun deleteAsksFirst() = runTest {
        val term = (repository.addTerm("Oslo", emptyList(), EntrySource.MANUAL) as Outcome.Ok).value
        val vm = DictionaryViewModel(repository)
        keepCollecting(vm.state)

        vm.onAction(DictionaryAction.AskDelete(DictionaryDelete.Word(term.id, term.term)))
        assertEquals("Oslo", vm.state.value.pendingDelete?.label)
        vm.onAction(DictionaryAction.ConfirmDelete)

        assertTrue(repository.terms.first().isEmpty())
        assertNull(vm.state.value.pendingDelete)
    }

    @Test
    fun storageFailureKeepsTheFormAndSaysSo() = runTest {
        val vm = DictionaryViewModel(repository)
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        repository.faults.failure = StorageFailed

        vm.onAction(DictionaryAction.Add)
        vm.onAction(DictionaryAction.FirstFieldChanged("Oslo"))
        vm.onAction(DictionaryAction.Save)

        assertEquals("Oslo", (vm.state.value.editor as DictionaryEditor.Word).term)
        assertEquals(listOf(DictionaryEffect.ShowMessage(R.string.message_save_failed)), effects)
    }
}
