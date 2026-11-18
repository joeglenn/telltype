package net.jbglenn.telltype.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.error
import net.jbglenn.telltype.core.common.info
import net.jbglenn.telltype.domain.port.BubbleSnooze

/**
 * [BubbleSnooze] in a small Preferences DataStore of its own, so a snooze survives a process restart. The end time
 * is stored as epoch milliseconds; [snoozedUntil] turns null by itself once [clock] passes it, and the stored
 * value is removed then.
 *
 * The stored snooze is read once when the object is created (until then [snoozedUntil] is null, for a few
 * milliseconds after a cold start). The wait for the end is a coroutine `delay` on [DispatcherProvider.default],
 * re-checked against the wall clock at least every [RECHECK], so a changed wall clock or time spent in deep sleep
 * (which a delay does not count) ends the snooze at most that much late. A snooze time that is not in the future
 * ends the snooze.
 *
 * Storage failures never escape: the snooze then holds in memory for this process and the failure is logged.
 *
 * @param scope lives as long as the app; owns the expiry wait.
 */
internal class DataStoreBubbleSnooze(
    private val store: DataStore<Preferences>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : BubbleSnooze {
    private val state = MutableStateFlow<Instant?>(null)
    private val lock = Mutex()
    private var loaded = false
    private var expiry: Job? = null

    override val snoozedUntil: StateFlow<Instant?> = state.asStateFlow()

    init {
        scope.launch(dispatchers.io) { lock.withLock { loadOnce() } }
    }

    override suspend fun snoozeUntil(until: Instant) {
        lock.withLock {
            loadOnce()
            if (until > clock.now()) {
                write("snooze") { it[KEY] = until.toEpochMilliseconds() }
                start(until)
                logger.info(TAG) { "bubble snoozed until $until" }
            } else {
                end()
            }
        }
    }

    override suspend fun resume() {
        lock.withLock {
            loadOnce()
            end()
        }
    }

    /** Applies the stored snooze the first time anything happens; a snooze that ended meanwhile is removed. */
    private suspend fun loadOnce() {
        if (loaded) return
        loaded = true
        val stored = read()?.let(Instant::fromEpochMilliseconds) ?: return
        if (stored > clock.now()) start(stored) else write("expire") { it.remove(KEY) }
    }

    private fun start(until: Instant) {
        expiry?.cancel()
        state.value = until
        expiry = scope.launch(dispatchers.default) {
            waitUntil(until)
            lock.withLock { if (state.value == until) expire(until) }
        }
    }

    private suspend fun end() {
        expiry?.cancel()
        expiry = null
        if (state.value != null) logger.info(TAG) { "bubble snooze ended" }
        state.value = null
        write("resume") { it.remove(KEY) }
    }

    private suspend fun expire(until: Instant) {
        expiry = null
        state.value = null
        write("expire") { preferences ->
            if (preferences[KEY] == until.toEpochMilliseconds()) preferences.remove(KEY)
        }
        logger.info(TAG) { "bubble snooze until $until is over" }
    }

    /** Waits on [clock] until [until], re-reading it at least every [RECHECK]. */
    private suspend fun waitUntil(until: Instant) {
        var left = until - clock.now()
        while (left > Duration.ZERO) {
            delay(left.coerceAtMost(RECHECK))
            left = until - clock.now()
        }
    }

    private suspend fun read(): Long? = withContext(dispatchers.io) {
        try {
            store.data.first()[KEY]
        } catch (failure: IOException) {
            logger.error(TAG, failure) { "the snooze could not be read; not snoozed" }
            null
        }
    }

    private suspend fun write(operation: String, change: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) {
            try {
                store.edit { change(it) }
            } catch (failure: IOException) {
                logger.error(TAG, failure) { "$operation could not be saved; it holds until the app restarts" }
            }
        }
    }

    private companion object {
        val TAG = LogTag("BubbleSnooze")
        val KEY = longPreferencesKey("bubble_snoozed_until_epoch_ms")

        /** The longest a snooze may outlast its time after a wall-clock change or deep sleep. */
        val RECHECK = 15.seconds
    }
}
