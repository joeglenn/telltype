package net.jbglenn.telltype.audio.platform

import net.jbglenn.telltype.core.model.audio.Interruption

/** Something registered with the system for the length of one recording. */
internal fun interface Registration {
    /** Unregisters. Idempotent; never throws. */
    fun release()
}

/** Audio focus for a recording (docs/PLAN.md section 7.3: music pauses and resumes). */
internal interface AudioFocus {
    /**
     * Requests exclusive transient focus. [onLost] is called, from any thread, when another app takes focus for
     * longer than a duck.
     *
     * @return the held focus, or null when the system refused (for example during a call); recording goes on then.
     */
    fun request(onLost: () -> Unit): Registration?
}

/** Events that end a recording early: the screen turning off and a call starting (docs/PLAN.md section 6.4). */
internal interface InterruptionMonitor {
    /** Calls [onInterruption], from any thread, for each such event until the registration is released. */
    fun watch(onInterruption: (Interruption) -> Unit): Registration
}

/** Whether the app holds the microphone permission right now. */
internal fun interface MicrophonePermission {
    /** True when `RECORD_AUDIO` is granted. */
    fun isGranted(): Boolean
}
