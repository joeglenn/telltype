package net.jbglenn.telltype.platform.focus

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.domain.port.FocusedFieldSource
import net.jbglenn.telltype.platform.service.AccessibilitySignal

/**
 * The focused editable field as a debounced, de-duplicated stream (docs/PLAN.md sections 7.1 and 9). Every focus
 * signal (filtered on the main thread) restarts a short quiet period; then the field is read once on the
 * accessibility dispatcher. A newer signal supersedes a pending reading. Null while nothing editable is focused,
 * while the service is disconnected, and while the screen is locked or a permission dialog is in front.
 *
 * @param signals filtered accessibility events.
 * @param connected whether the accessibility service is connected.
 * @param read one blocking reading (run on the accessibility dispatcher).
 * @param debounce the quiet period after the last signal before reading.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class FocusTracker(
    signals: Flow<AccessibilitySignal>,
    connected: StateFlow<Boolean>,
    private val read: () -> FocusReading,
    private val dispatchers: DispatcherProvider,
    scope: CoroutineScope,
    debounce: Duration = DEFAULT_DEBOUNCE,
) : FocusedFieldSource {
    /** Every distinct reading, starting with [FocusReading.Nothing]. */
    val readings: StateFlow<FocusReading> = connected
        .flatMapLatest { isConnected ->
            if (isConnected) {
                signals
                    .filter { it == AccessibilitySignal.FocusMayHaveChanged }
                    .onStart { emit(AccessibilitySignal.FocusMayHaveChanged) }
                    .debounce(debounce)
                    .mapLatest { withContext(dispatchers.accessibility) { read() } }
            } else {
                flowOf(FocusReading.Nothing)
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, FocusReading.Nothing)

    override val focused: StateFlow<FieldSnapshot?> =
        readings.map { it.snapshot }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The screen must not show the bubble: locked, asleep, or a permission dialog in front. */
    val screenBlocked: StateFlow<Boolean> = readings.map { it.blocked }.stateIn(scope, SharingStarted.Eagerly, false)

    private companion object {
        val DEFAULT_DEBOUNCE = 80.milliseconds
    }
}
