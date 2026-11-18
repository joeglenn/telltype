package net.jbglenn.telltype.platform.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * The lifecycle, view-model store and saved-state owners a `ComposeView` needs outside an activity (docs/PLAN.md
 * section 7.2), attached by hand to the overlay's view tree. Main thread only. Resumed while the overlay window is
 * shown, destroyed (with its view models cleared) when it is removed; not reused afterwards.
 */
internal class OverlayViewTreeOwner :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    init {
        savedState.performAttach()
        savedState.performRestore(null)
    }

    /** The window is shown: Compose may run. */
    fun resume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** The window is gone: stops Compose and clears the view models. */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
