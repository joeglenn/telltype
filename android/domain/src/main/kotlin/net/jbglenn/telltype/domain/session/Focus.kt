package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.UnavailableReason

/** Bubble eligibility (docs/PLAN.md sections 4.1 and 7.5); see `evaluateFocus`. */
internal fun focusFor(input: FocusInput): Focus {
    val snapshot = input.snapshot
    return when {
        snapshot == null || !snapshot.keyboardVisible -> Focus.None
        !snapshot.target.kind.acceptsDictation -> Focus.None
        input.appRule?.takeIf { it.app == snapshot.target.app }?.excluded == true -> Focus.None
        !input.bubble.enabled || input.snoozed -> Focus.None
        !input.permissions.accessibilityEnabled -> Focus.None
        !input.permissions.microphoneGranted -> Focus.Blocked(UnavailableReason.MICROPHONE_PERMISSION_MISSING)
        !input.serverConfigured -> Focus.Blocked(UnavailableReason.SERVER_NOT_CONFIGURED)
        snapshot.hasSelection -> Focus.Available(snapshot.target, BubbleMode.COMMAND)
        else -> Focus.Available(snapshot.target, BubbleMode.DICTATE)
    }
}
