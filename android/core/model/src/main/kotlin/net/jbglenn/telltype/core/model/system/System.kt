package net.jbglenn.telltype.core.model.system

import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.DictationError
import net.jbglenn.telltype.core.model.lengthOnly

/**
 * A snapshot of every permission and system switch the app depends on (docs/PLAN.md sections 5.3 and 7.6).
 * Re-read whenever the setup screen resumes.
 *
 * @property microphoneGranted `RECORD_AUDIO` is granted.
 * @property accessibilityEnabled the app's accessibility service is switched on and bound.
 * @property notificationsGranted `POST_NOTIFICATIONS` is granted.
 * @property localNetworkGranted `ACCESS_LOCAL_NETWORK` is granted; always true below Android 17, where it does
 *   not exist.
 * @property advancedProtectionOn Android Advanced Protection mode is on, which limits accessibility services
 *   to verified tools; always false below Android 16.
 */
public data class PermissionStatus(
    public val microphoneGranted: Boolean,
    public val accessibilityEnabled: Boolean,
    public val notificationsGranted: Boolean,
    public val localNetworkGranted: Boolean,
    public val advancedProtectionOn: Boolean,
)

/** A system notification the app posts (docs/PLAN.md sections 8.5 and 10). */
public sealed interface Notice {
    /**
     * A queued recording was transcribed later; the notification offers Copy. Never typed automatically.
     *
     * @property session the session the recording came from.
     * @property text the cleaned text. Dictated content: never logged.
     */
    public data class TranscribedLater(public val session: SessionId, public val text: String) : Notice {
        override fun toString(): String = "TranscribedLater(session=$session, text=${text.lengthOnly()})"
    }

    /**
     * Sessions interrupted by a crash were found at start and queued: "Recovered N dictations".
     *
     * @property count how many; `>= 1`.
     */
    public data class DictationsRecovered(public val count: Int) : Notice

    /** The accessibility service is off, so the bubble cannot show; the notification leads to Fix. */
    public data object AccessibilityOff : Notice

    /** Advanced Protection is on, so the bubble is gone; text still arrives through clipboard and History. */
    public data object AdvancedProtectionOn : Notice

    /**
     * A session ended in a problem while the bubble could not be seen (screen off, keyboard closed), so the
     * problem is not silent.
     *
     * @property error the problem.
     */
    public data class ProblemWhileHidden(public val error: DictationError) : Notice
}
