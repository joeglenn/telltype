package net.jbglenn.telltype.testing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.core.model.system.PermissionStatus
import net.jbglenn.telltype.domain.port.ClipboardPort
import net.jbglenn.telltype.domain.port.FixLauncher
import net.jbglenn.telltype.domain.port.HapticsPort
import net.jbglenn.telltype.domain.port.NotifierPort
import net.jbglenn.telltype.domain.port.PermissionStatusSource
import net.jbglenn.telltype.domain.port.ServerHealthSource

/** Clipboard fake: remembers every copied text; [content] is the latest. */
public class FakeClipboard : ClipboardPort {
    /** [copy]: the argument is the text. */
    public val copyCall: ScriptedCall<String, Unit> = ScriptedCall { }

    /** What the clipboard holds now, null when nothing was copied. */
    public val content: String? get() = copyCall.calls.lastOrNull()

    override suspend fun copy(text: String) {
        copyCall.answer(text)
    }
}

/** Haptics fake: remembers every cue. */
public class FakeHaptics : HapticsPort {
    private val cues = mutableListOf<FeedbackCue>()

    /** Every cue played, oldest first. */
    public val played: List<FeedbackCue> get() = cues.toList()

    override fun play(cue: FeedbackCue) {
        cues += cue
    }
}

/** Notifier fake: remembers every notice posted and cancelled. */
public class FakeNotifier : NotifierPort {
    /** [post]: the argument is the notice. */
    public val postCall: ScriptedCall<Notice, Unit> = ScriptedCall { }

    /** [cancel]: the argument is the notice. */
    public val cancelCall: ScriptedCall<Notice, Unit> = ScriptedCall { }

    override suspend fun post(notice: Notice) {
        postCall.answer(notice)
    }

    override suspend fun cancel(notice: Notice) {
        cancelCall.answer(notice)
    }
}

/** Fix launcher fake: remembers every fix opened. */
public class FakeFixLauncher : FixLauncher {
    /** [open]: the argument is the fix. */
    public val openCall: ScriptedCall<FixTarget, Unit> = ScriptedCall { }

    override suspend fun open(fix: FixTarget) {
        openCall.answer(fix)
    }
}

/**
 * Health source fake. [probe] publishes and returns its scripted answer; by default the current value (so it starts
 * as [ServerReachability.Unknown]). Set the value directly with [publish].
 */
public class FakeServerHealthSource : ServerHealthSource {
    private val state = MutableStateFlow<ServerReachability>(ServerReachability.Unknown)

    /** [probe]. */
    public val probeCall: ScriptedCall<Unit, ServerReachability> = ScriptedCall { state.value }

    override val reachability: StateFlow<ServerReachability> = state.asStateFlow()

    override suspend fun probe(): ServerReachability = probeCall.answer(Unit).also { state.value = it }

    /** Sets the published value without a probe. */
    public fun publish(reachability: ServerReachability) {
        state.value = reachability
    }
}

/**
 * Permission source fake, everything granted at first. Change what the device has with [changeDevice]; it becomes
 * visible in [status] at the next [refresh], like the real source.
 */
public class FakePermissionStatusSource(initial: PermissionStatus = Fixtures.permissionsGranted()) :
    PermissionStatusSource {
    private val state = MutableStateFlow(initial)
    private var device: PermissionStatus = initial

    /** How many times [refresh] was called. */
    public var refreshCount: Int = 0
        private set

    override val status: StateFlow<PermissionStatus> = state.asStateFlow()

    override suspend fun refresh(): PermissionStatus {
        refreshCount++
        state.value = device
        return device
    }

    /** Changes the device's real permission state (granted or revoked). */
    public fun changeDevice(status: PermissionStatus) {
        device = status
    }
}
