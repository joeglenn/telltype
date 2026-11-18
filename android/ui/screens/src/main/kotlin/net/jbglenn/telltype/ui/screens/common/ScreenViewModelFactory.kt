package net.jbglenn.telltype.ui.screens.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject
import javax.inject.Provider
import net.jbglenn.telltype.ui.screens.diagnostics.DiagnosticsViewModel
import net.jbglenn.telltype.ui.screens.dictionary.DictionaryViewModel
import net.jbglenn.telltype.ui.screens.history.HistoryViewModel
import net.jbglenn.telltype.ui.screens.home.HomeViewModel
import net.jbglenn.telltype.ui.screens.settings.ModelsViewModel
import net.jbglenn.telltype.ui.screens.settings.SettingsViewModel
import net.jbglenn.telltype.ui.screens.setup.SetupViewModel
import net.jbglenn.telltype.ui.screens.snippets.SnippetsViewModel
import net.jbglenn.telltype.ui.screens.styles.StylesViewModel

/**
 * Creates the screens' ViewModels from their Hilt providers. `:app` injects one and passes it to
 * `TelltypeNavHost`; each ViewModel is still scoped to its navigation destination.
 *
 * The ViewModels are constructor-injected rather than `@HiltViewModel`: Hilt makes every `@HiltViewModel`
 * reachable from the app's graph as soon as this module is on the classpath, which would fail the app build
 * until every port has a binding. Through this factory they are checked when `:app` first injects it.
 */
class ScreenViewModelFactory @Inject constructor(
    private val providers: Map<Class<*>, @JvmSuppressWildcards Provider<ViewModel>>,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val provider = requireNotNull(providers[modelClass]) { "No ViewModel provider for ${modelClass.name}" }
        return checkNotNull(modelClass.cast(provider.get()))
    }
}

/** Registers every screen ViewModel with [ScreenViewModelFactory]. */
@Module
@InstallIn(SingletonComponent::class)
internal interface ScreenViewModelsModule {
    @Binds
    @IntoMap
    @ClassKey(SetupViewModel::class)
    fun setup(viewModel: SetupViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(HomeViewModel::class)
    fun home(viewModel: HomeViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(HistoryViewModel::class)
    fun history(viewModel: HistoryViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(DictionaryViewModel::class)
    fun dictionary(viewModel: DictionaryViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(SnippetsViewModel::class)
    fun snippets(viewModel: SnippetsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(StylesViewModel::class)
    fun styles(viewModel: StylesViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(SettingsViewModel::class)
    fun settings(viewModel: SettingsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(ModelsViewModel::class)
    fun models(viewModel: ModelsViewModel): ViewModel

    @Binds
    @IntoMap
    @ClassKey(DiagnosticsViewModel::class)
    fun diagnostics(viewModel: DiagnosticsViewModel): ViewModel
}
