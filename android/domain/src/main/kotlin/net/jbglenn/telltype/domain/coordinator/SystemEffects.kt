package net.jbglenn.telltype.domain.coordinator

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.FeedbackCue

/** `Haptic`, `ShowChip`, `OpenFix` and `ProbeServerHealth`: fire and forget, each bounded. */
internal class SystemEffects(private val ctx: CoordinatorContext, private val chips: MutableSharedFlow<Chip>) {
    /** Plays [cue] (a non-suspending port call). */
    fun haptic(cue: FeedbackCue) {
        ctx.calls.quietly("haptic") { ctx.ports.haptics.play(cue) }
    }

    /** Shows [chip]; the chip flow drops the oldest chip rather than block. */
    fun chip(chip: Chip) {
        chips.tryEmit(chip)
    }

    /** Opens [fix]'s screen. */
    fun openFix(fix: FixTarget) {
        ctx.work.launch { ctx.calls.call("fix launcher", ctx.config.system, Unit) { ctx.ports.fixLauncher.open(fix) } }
    }

    /** Probes the server; the health source publishes the result for the offline mark. */
    fun probe() {
        ctx.work.launch {
            ctx.calls.call("health probe", ctx.config.healthProbe, Unit) {
                ctx.ports.health.probe()
            }
        }
    }
}
