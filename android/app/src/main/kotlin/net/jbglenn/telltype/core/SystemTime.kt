package net.jbglenn.telltype.core

import android.os.SystemClock
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant
import net.jbglenn.telltype.core.common.Clock
import net.jbglenn.telltype.core.common.IdGenerator
import net.jbglenn.telltype.core.model.RecordingId
import net.jbglenn.telltype.core.model.SessionId

/** The device clock: wall time from the system, durations from `elapsedRealtime` (keeps counting in deep sleep). */
internal class SystemClockSource @Inject constructor() : Clock {
    override fun now(): Instant = kotlin.time.Clock.System.now()

    override fun monotonic(): Duration = SystemClock.elapsedRealtimeNanos().nanoseconds
}

/** Random UUIDs for sessions and recordings. */
internal class UuidIdGenerator @Inject constructor() : IdGenerator {
    override fun newSessionId(): SessionId = SessionId(UUID.randomUUID().toString())

    override fun newRecordingId(): RecordingId = RecordingId(UUID.randomUUID().toString())
}
