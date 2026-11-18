package net.jbglenn.telltype.domain.port

import kotlinx.coroutines.flow.StateFlow
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.core.model.system.PermissionStatus

/** The system clipboard, for hand-off and Copy. Implemented by `:platform`. */
public interface ClipboardPort {
    /** Puts [text] on the clipboard, always flagged sensitive (kept out of clipboard previews). */
    public suspend fun copy(text: String)
}

/** Haptic and sound feedback (Appendix H). Implemented by `:platform`; honours the haptics and sound settings. */
public interface HapticsPort {
    /** Plays [cue]. Fire and forget; does nothing when feedback is switched off. */
    public fun play(cue: FeedbackCue)
}

/** System notifications (queued results, problems the bubble cannot show). Implemented by `:platform`. */
public interface NotifierPort {
    /** Posts [notice]; does nothing visible when the notification permission is missing. */
    public suspend fun post(notice: Notice)

    /**
     * Removes the notification [post] showed for [notice] (the one of its kind, or of its session for results),
     * e.g. "Bubble is off" once the service is back. Does nothing when none shows.
     */
    public suspend fun cancel(notice: Notice)
}

/** Opens the screen where a problem is fixed (setup steps, Android settings). Implemented by `:platform`. */
public interface FixLauncher {
    /** Opens [fix]'s screen in a new task. */
    public suspend fun open(fix: FixTarget)
}

/**
 * Server health (Appendix C `/healthz`), for the offline mark, setup's Test button and Diagnostics. Implemented by
 * `:network`.
 */
public interface ServerHealthSource {
    /** The latest result; [ServerReachability.Unknown] before the first probe. */
    public val reachability: StateFlow<ServerReachability>

    /** Probes the addresses in order now (with timeouts), publishes and returns the result. */
    public suspend fun probe(): ServerReachability
}

/** Permission and system-switch state (section 7.6). Implemented by `:platform`. */
public interface PermissionStatusSource {
    /** The latest snapshot. */
    public val status: StateFlow<PermissionStatus>

    /** Re-reads every permission now (setup calls this on each resume), publishes and returns the result. */
    public suspend fun refresh(): PermissionStatus
}
