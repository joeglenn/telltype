package net.jbglenn.telltype.audio.capture

import javax.inject.Inject
import net.jbglenn.telltype.audio.platform.AudioFocus
import net.jbglenn.telltype.audio.platform.InterruptionMonitor
import net.jbglenn.telltype.audio.platform.MicrophonePermission

/**
 * The parts of the system a recording uses: the microphone, the permission, audio focus and the interruption
 * signals. Grouped so the recorder takes one dependency for them.
 *
 * @property sources opens the microphone.
 * @property permission the microphone permission.
 * @property focus audio focus.
 * @property monitor screen-off and call signals.
 */
internal class CaptureDevices @Inject constructor(
    val sources: PcmSourceFactory,
    val permission: MicrophonePermission,
    val focus: AudioFocus,
    val monitor: InterruptionMonitor,
)
