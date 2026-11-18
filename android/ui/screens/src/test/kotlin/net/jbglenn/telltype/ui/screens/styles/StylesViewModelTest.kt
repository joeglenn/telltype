package net.jbglenn.telltype.ui.screens.styles

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.FakeAppRuleRepository
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.ui.screens.MainDispatcherRule
import net.jbglenn.telltype.ui.screens.R
import net.jbglenn.telltype.ui.screens.collectInto
import net.jbglenn.telltype.ui.screens.keepCollecting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StylesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = FakeSettingsRepository()
    private val rules = FakeAppRuleRepository()

    @Test
    fun everyCategoryHasATone() = runTest {
        val vm = StylesViewModel(settings, rules)
        keepCollecting(vm.state)

        assertEquals(AppCategory.entries, vm.state.value.tones.map { it.category })
    }

    @Test
    fun changingAToneSavesIt() = runTest {
        val vm = StylesViewModel(settings, rules)
        keepCollecting(vm.state)

        vm.onAction(StylesAction.SetTone(AppCategory.EMAIL, Tone.CASUAL))

        assertEquals(Tone.CASUAL, settings.current().styles.toneFor(AppCategory.EMAIL))
    }

    @Test
    fun exclusionNeedsAPackageNameAndRemovingItDeletesTheRule() = runTest {
        val vm = StylesViewModel(settings, rules)
        keepCollecting(vm.state)

        vm.onAction(StylesAction.AddExclusion)
        vm.onAction(StylesAction.AppChanged("not a package"))
        vm.onAction(StylesAction.Save)
        assertEquals(R.string.styles_error_package, vm.state.value.editor?.error)

        vm.onAction(StylesAction.AppChanged("com.example.bank"))
        vm.onAction(StylesAction.Save)
        assertNull(vm.state.value.editor)
        assertEquals(listOf(AppPackage("com.example.bank")), vm.state.value.excluded.map { it.app })

        vm.onAction(StylesAction.RemoveExclusion(AppPackage("com.example.bank")))
        assertTrue(rules.rules.first().isEmpty())
    }

    @Test
    fun overrideNeedsACategoryOrATone() = runTest {
        val vm = StylesViewModel(settings, rules)
        keepCollecting(vm.state)

        vm.onAction(StylesAction.AddOverride)
        vm.onAction(StylesAction.AppChanged("com.example.notes"))
        vm.onAction(StylesAction.Save)
        assertEquals(R.string.styles_error_override_empty, vm.state.value.editor?.error)

        vm.onAction(StylesAction.ToneChanged(Tone.FORMAL))
        vm.onAction(StylesAction.Save)

        val rule = rules.rules.first().single()
        assertEquals(AppRule(AppPackage("com.example.notes"), null, Tone.FORMAL, excluded = false), rule)
    }

    @Test
    fun storageFailureIsReported() = runTest {
        val vm = StylesViewModel(settings, rules)
        keepCollecting(vm.state)
        val effects = collectInto(vm.effects)
        settings.faults.failure = StorageFailed

        vm.onAction(StylesAction.SetTone(AppCategory.CODE, Tone.FORMAL))

        assertEquals(listOf(StylesEffect.ShowMessage(R.string.message_save_failed)), effects)
    }
}
