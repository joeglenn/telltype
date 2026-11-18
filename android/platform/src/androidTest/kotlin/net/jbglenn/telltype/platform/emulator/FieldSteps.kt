package net.jbglenn.telltype.platform.emulator

import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.field.FieldSnapshot

/** Shared steps of the field tests. */
internal object FieldSteps {
    /**
     * Opens [type] and returns its snapshot once the keyboard is up. Screens focus their field at start; a field that
     * is not focused by then (the web page) is tapped once.
     */
    fun focus(type: HostField, text: String? = null, selection: IntRange? = null): FieldSnapshot {
        Emulator.open(type, text, selection)
        val ready: (FieldSnapshot?) -> Boolean = {
            it != null && it.target.app.name == Emulator.HOST &&
                it.keyboardVisible
        }
        val early = runCatching { Emulator.awaitSnapshot(4.seconds, ready) }.getOrNull()
        return early ?: run {
            checkNotNull(type.find()).click()
            checkNotNull(Emulator.awaitSnapshot(predicate = ready))
        }
    }
}
