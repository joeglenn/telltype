package net.jbglenn.telltype.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.system.BubbleDrop
import net.jbglenn.telltype.core.model.system.BubblePlacement
import net.jbglenn.telltype.domain.port.BubbleWindowPort
import net.jbglenn.telltype.domain.port.MicrophoneServicePort

/**
 * Bubble window fake. Shows nothing until [publish]; [dragBy] moves the published placement, [dragEnded] answers
 * with [dropAnswer] (by default docked to the current side) and ends the drag.
 */
public class FakeBubbleWindow : BubbleWindowPort {
    private val state = MutableStateFlow<BubblePlacement?>(null)
    private val recordedDrags = mutableListOf<Pair<Float, Float>>()
    private val recordedDrops = mutableListOf<BubblePlacement?>()

    /** Every [dragBy] delta, oldest first. */
    public val drags: List<Pair<Float, Float>> get() = recordedDrags.toList()

    /** The placement at each [dragEnded] (null when nothing was shown), oldest first. */
    public val drops: List<BubblePlacement?> get() = recordedDrops.toList()

    /** How [dragEnded] answers, given the placement at release. */
    public var dropAnswer: (BubblePlacement?) -> BubbleDrop = { placement ->
        BubbleDrop.Docked(placement?.side ?: BubbleSide.RIGHT)
    }

    override val placement: StateFlow<BubblePlacement?> = state.asStateFlow()

    /** Sets the placement as the overlay host would (null: hidden). */
    public fun publish(placement: BubblePlacement?) {
        state.value = placement
    }

    override fun dragBy(dx: Float, dy: Float) {
        recordedDrags += dx to dy
        state.value = state.value?.let { it.copy(x = it.x + dx.toInt(), y = it.y + dy.toInt(), dragging = true) }
    }

    override fun dragEnded(): BubbleDrop {
        val atRelease = state.value
        recordedDrops += atRelease
        val drop = dropAnswer(atRelease)
        state.value = atRelease?.copy(
            side = (drop as? BubbleDrop.Docked)?.side ?: atRelease.side,
            dragging = false,
            inSnoozeZone = false,
        )
        return drop
    }
}

/** Microphone service fake. Starts successfully by default; [running] tells whether it is in the foreground. */
public class FakeMicrophoneService : MicrophoneServicePort {
    /** [start]. */
    public val startCall: ScriptedCall<Unit, Outcome<Unit, CaptureError>> = ScriptedCall { Outcome.Ok(Unit) }

    /** How many times [stop] was called. */
    public var stopCount: Int = 0
        private set

    /** The service is in the foreground: the last [start] succeeded and no [stop] came after it. */
    public var running: Boolean = false
        private set

    override suspend fun start(): Outcome<Unit, CaptureError> = startCall.answer(Unit).also { result ->
        if (result is Outcome.Ok) running = true
    }

    override suspend fun stop() {
        stopCount++
        running = false
    }
}
