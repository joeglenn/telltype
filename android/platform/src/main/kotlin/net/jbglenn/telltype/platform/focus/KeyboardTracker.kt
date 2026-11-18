package net.jbglenn.telltype.platform.focus

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.platform.service.AccessibilitySignal

/**
 * Whether the keyboard is open for the focused field (docs/PLAN.md section 5.1: the bubble shows while a field has
 * the focus AND the keyboard is open; board B7). Two sources, both needed:
 * - the focus reading's keyboard bounds (debounced, so the bubble appears only once the reading it is placed from
 *   knows where the keyboard is);
 * - a reading of the windows on every windows-changed event, without the quiet period, so the bubble goes in the
 *   same event in which the input-method window is gone.
 * Hiding is therefore immediate and showing waits for the debounced reading.
 *
 * @param signals filtered accessibility events.
 * @param connected whether the accessibility service is connected.
 * @param readings the focus tracker's readings.
 * @param keyboardShown one reading: an input-method window is on the screen (it picks its own dispatcher).
 * @param uptimeMillis the clock the event times are on, for the log line that measures the delay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class KeyboardTracker(
    signals: Flow<AccessibilitySignal>,
    connected: StateFlow<Boolean>,
    readings: StateFlow<FocusReading>,
    private val keyboardShown: suspend () -> Boolean,
    private val logger: Logger,
    scope: CoroutineScope,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val seenNow: Flow<Boolean> = connected.flatMapLatest { isConnected ->
        if (isConnected) {
            signals
                .filterIsInstance<AccessibilitySignal.KeyboardMayHaveChanged>()
                .conflate()
                .map { signal -> Sighting(keyboardShown(), signal) }
                .distinctUntilChangedBy { it.shown }
                .onEach { if (!it.shown) logGone(it.signal) }
                .map { it.shown }
                .onStart { emit(true) }
        } else {
            flowOf(false)
        }
    }

    /** True while a field has the focus and the keyboard is open for it. */
    val open: StateFlow<Boolean> = combine(readings, seenNow) { reading, shown ->
        shown && reading.snapshot?.keyboardBounds != null
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun logGone(signal: AccessibilitySignal.KeyboardMayHaveChanged) {
        val delay = uptimeMillis() - signal.eventUptimeMillis
        logger.info(TAG) { "keyboard gone: read $delay ms after the event" }
    }

    private data class Sighting(val shown: Boolean, val signal: AccessibilitySignal.KeyboardMayHaveChanged)

    private companion object {
        val TAG = LogTag("Keyboard")
    }
}
