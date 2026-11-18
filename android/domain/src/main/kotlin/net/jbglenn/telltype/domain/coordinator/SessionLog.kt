package net.jbglenn.telltype.domain.coordinator

import net.jbglenn.telltype.core.model.session.Session
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * The info line that says how a session reached an end point (brief M1): Done (with how the text got there, its
 * length and the chips offered), a Problem (its error code), or back to Ready, Hidden or Unavailable (which event
 * ended it: Undo, a timer, Cancel, a focus change). Null when [after] is not such a point, or nothing changed.
 * Only lengths, codes and enum names: never the text, the selection, the instruction or the field's hint.
 */
internal fun sessionEndLine(event: SessionEvent, before: SessionState, after: SessionState): String? {
    val cause = event.describe()
    return when {
        after is SessionState.Done && (before !is SessionState.Done || before.receipt != after.receipt) ->
            "${after.session.describe()} done via ${after.receipt.via}: ${after.receipt.text.length} chars " +
                "replacing ${after.receipt.replaced.length}, chips ${after.actions.sorted()} ($cause)"

        after is SessionState.Problem && before != after ->
            "${after.session?.describe() ?: "no session"} problem ${after.error.code}, " +
                "audio ${if (after.keptAudio == null) "not kept" else "kept"}, " +
                "actions ${after.actions.sorted()} ($cause)"

        after.isRest() && !before.isRest() ->
            "${before.session()?.describe() ?: "start"} ended: ${before::class.simpleName} -> " +
                "${after::class.simpleName} ($cause)"

        else -> null
    }
}

private fun SessionState.isRest(): Boolean =
    this is SessionState.Ready || this is SessionState.Hidden || this is SessionState.Unavailable

private fun SessionState.session(): Session? = when (this) {
    is SessionState.Listening -> session
    is SessionState.Working -> session
    is SessionState.Done -> session
    is SessionState.Problem -> session
    else -> null
}

private fun Session.describe(): String = "session ${id.value} (${mode.name.lowercase()})"

private fun SessionEvent.describe(): String = when (this) {
    is SessionEvent.ActionTapped -> "action ${action.name}"
    is SessionEvent.TimerFired -> "timer ${timer.name}"
    is SessionEvent.Interrupted -> "interrupted: ${cause.name}"
    else -> this::class.simpleName.orEmpty()
}
