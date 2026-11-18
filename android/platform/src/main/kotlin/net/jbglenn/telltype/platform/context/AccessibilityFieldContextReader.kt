package net.jbglenn.telltype.platform.context

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.FieldHandle
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.domain.port.FieldContextReader
import net.jbglenn.telltype.platform.field.FieldAccess

/**
 * Reads the text around the cursor at record start (docs/PLAN.md section 4.3). Best effort by contract: an
 * incognito, password or numeric field, a field that is gone or unreadable, and a read that misses [timeout] all
 * give [FieldContext.Empty]. The limits are applied by [ContextSlicer].
 *
 * The node read blocks the accessibility thread, so it runs as a separate job in [scope] and the caller waits for it
 * at most [timeout]: a read stuck in a cross-process call cannot delay the recording start. A late result is dropped
 * (reading has no side effects).
 *
 * @param resolve the field behind a handle, null when unknown.
 */
internal class AccessibilityFieldContextReader(
    private val resolve: (FieldHandle) -> FieldAccess?,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : FieldContextReader {
    override suspend fun read(target: FieldTarget, charsBefore: Int, charsAfter: Int): FieldContext {
        if (target.incognito || !target.kind.acceptsDictation) return FieldContext.Empty
        val reading = scope.async(dispatchers.accessibility) { resolve(target.handle)?.read() }
        val state = withTimeoutOrNull(timeout) { reading.await() }
        if (state == null) reading.cancel()
        return state?.let { ContextSlicer.slice(it, charsBefore, charsAfter) } ?: FieldContext.Empty
    }

    private companion object {
        val DEFAULT_TIMEOUT = 300.milliseconds
    }
}
