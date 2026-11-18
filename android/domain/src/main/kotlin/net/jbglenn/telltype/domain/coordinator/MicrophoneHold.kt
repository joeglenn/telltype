package net.jbglenn.telltype.domain.coordinator

import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.CaptureError
import net.jbglenn.telltype.core.model.error.RecorderFailed
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * The foreground microphone service (docs/PLAN.md section 7.3): started at record start, stopped as soon as the
 * state no longer records or processes, on every path (success, problem, cancel, abort, close).
 */
internal class MicrophoneHold(private val ctx: CoordinatorContext) {
    private var held = false

    /** Starts the service. Marked held before the call, so a start cancelled half-way is still stopped. */
    suspend fun acquire(): Outcome<Unit, CaptureError> {
        held = true
        val failure: Outcome<Unit, CaptureError> = Outcome.Err(RecorderFailed)
        return ctx.calls.call("microphone service start", ctx.config.microphoneStart, failure) {
            ctx.ports.microphone.start()
        }
    }

    /** Stops the service (after any queued recorder work) when the current state no longer needs it. */
    fun releaseIfIdle() {
        if (held && !ctx.state.usesMicrophone()) {
            held = false
            ctx.captureLane.launch(ctx.work) { stop() }
        }
    }

    /** Stops the service now if it may be running (close). */
    suspend fun releaseNow() {
        if (held) {
            held = false
            stop()
        }
    }

    private suspend fun stop() {
        ctx.calls.call("microphone service stop", ctx.config.microphoneStart, Unit) { ctx.ports.microphone.stop() }
    }

    private fun SessionState.usesMicrophone(): Boolean =
        this is SessionState.Arming || this is SessionState.Listening || this is SessionState.Working
}
