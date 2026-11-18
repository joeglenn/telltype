package net.jbglenn.telltype.platform.edits

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.domain.port.FieldEditWatcher
import net.jbglenn.telltype.platform.field.FieldAccess
import net.jbglenn.telltype.platform.service.AccessibilitySignal

/**
 * Watches one field after a dictation (docs/PLAN.md sections 4.5 and 7.1). Text-changed events are switched on
 * through [switch] only while a watch runs, and off again when the last watch ends. Each change in the field's
 * window re-reads the field on the accessibility dispatcher; every text that differs from the previous one is
 * emitted (the text at the start of the watch is the baseline, not emitted). The flow ends after the window, when
 * the field is gone, or when the collector cancels. Incognito fields (no personalised learning) and fields that take
 * no dictation are not watched at all.
 *
 * @param resolve the field behind a handle, null when unknown.
 * @param signals filtered accessibility events.
 */
internal class AccessibilityFieldEditWatcher(
    private val resolve: (FieldHandle) -> FieldAccess?,
    private val signals: Flow<AccessibilitySignal>,
    private val switch: TextChangeSwitch,
    private val dispatchers: DispatcherProvider,
) : FieldEditWatcher {
    override fun edits(target: FieldTarget, window: Duration): Flow<String> =
        if (target.incognito || !target.kind.acceptsDictation) emptyFlow() else watch(target, window)

    private fun watch(target: FieldTarget, window: Duration): Flow<String> = flow {
        val field = resolve(target.handle) ?: return@flow
        switch.acquire()
        try {
            withTimeoutOrNull(window) { emitAll(changes(field)) }
        } finally {
            switch.release()
        }
    }.flowOn(dispatchers.accessibility)

    private fun changes(field: FieldAccess): Flow<String> = flow {
        var last = field.read()?.content
        signals
            .filter { it is AccessibilitySignal.TextChanged && it.windowId == field.windowId }
            .map { field.read() }
            .transformWhile { state ->
                state?.content?.takeIf { state.kind.acceptsDictation }?.let { emit(it) }
                state != null
            }
            .collect { text ->
                if (text != last) {
                    last = text
                    emit(text)
                }
            }
    }
}
