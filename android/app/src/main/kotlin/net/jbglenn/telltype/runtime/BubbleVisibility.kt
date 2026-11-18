package net.jbglenn.telltype.runtime

import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionState

/**
 * Whether the bubble window shows for this state. Done and Problem outlive the field they belong to (Paste, Retry,
 * the kept audio), so the session state keeps them when the focus moves; but where the bubble may not appear
 * ([Focus.None]: a password field, an excluded app, a snoozed or disabled bubble, no field at all) they wait
 * hidden until a field that accepts dictation has the focus (docs/PLAN.md section 10: "Excluded or password field:
 * bubble hidden"). A running session (Arming, Listening, Working) stays visible so that it can always be stopped.
 */
internal fun SessionState.showsBubble(): Boolean = when (this) {
    SessionState.Hidden -> false

    is SessionState.Done -> focus != Focus.None

    is SessionState.Problem -> focus != Focus.None

    is SessionState.Ready, is SessionState.Unavailable, is SessionState.Arming, is SessionState.Listening,
    is SessionState.Working,
    -> true
}

/**
 * Whether this state's bubble shows only while the keyboard is open (docs/PLAN.md section 5.1, board B7): the idle
 * bubble (Ready, Unavailable) goes with the keyboard. A running session (Arming, Listening, Working) keeps its bubble
 * so that it can always be stopped, and Done and Problem keep theirs (Paste, Retry) as before.
 */
internal fun SessionState.needsKeyboard(): Boolean = this is SessionState.Ready || this is SessionState.Unavailable
