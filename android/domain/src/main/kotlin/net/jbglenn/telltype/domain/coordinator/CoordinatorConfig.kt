package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every timeout and duration the coordinator owns (docs/PLAN.md section 9: every external wait has a timeout). The
 * ports bound their own work too; these are the coordinator's outer limits, so a port that hangs can never stall
 * a session. All values are initial targets.
 *
 * @property microphoneStart `MicrophoneServicePort.start` and `stop`.
 * @property recorderStart `AudioCapture.start`.
 * @property recorderStop `AudioCapture.stop` and `cancel`.
 * @property storage each repository or recording-store call, and each read of a repository flow.
 * @property contextRead `FieldContextReader.read`, and how long `Clean` waits for that read.
 * @property prepare `SpeechToTextEngine.prepare`.
 * @property transcription `SpeechToTextEngine.transcribe`; a timeout counts as `ServerUnreachable`.
 * @property modelCeiling the cleanup wait when the deadline setting is "always wait", and the Command Mode wait.
 * @property insertion each `TextInserter` call; a timeout counts as `InsertNotConfirmed`.
 * @property system clipboard, fix launcher and notifier calls.
 * @property healthProbe `ServerHealthSource.probe`.
 * @property learningWindow how long a field is watched for corrections after a dictation (section 4.5).
 * @property doneChips `SessionTimer.DONE_CHIPS` (Appendix H).
 * @property commandUndo `SessionTimer.COMMAND_UNDO` (section 4.7).
 * @property problemDismiss `SessionTimer.PROBLEM_DISMISS`.
 * @property handoffLimit `SessionTimer.HANDOFF_LIMIT`: how long a hand-off's chip waits at most for a field.
 * @property sessionWarningLead how long before the session limit `SessionTimer.SESSION_WARNING` fires.
 * @property teardown how long [SessionCoordinator.close] may spend releasing the microphone.
 * @property inboxCapacity events waiting for the loop; [SessionCoordinator.dispatch] drops (and logs) beyond it.
 */
public data class CoordinatorConfig(
    public val microphoneStart: Duration = Defaults.Device,
    public val recorderStart: Duration = Defaults.Device,
    public val recorderStop: Duration = Defaults.Device,
    public val storage: Duration = Defaults.Device,
    public val contextRead: Duration = Defaults.ContextRead,
    public val prepare: Duration = Defaults.Prepare,
    public val transcription: Duration = Defaults.Transcription,
    public val modelCeiling: Duration = Defaults.ModelCeiling,
    public val insertion: Duration = Defaults.Insertion,
    public val system: Duration = Defaults.Device,
    public val healthProbe: Duration = Defaults.HealthProbe,
    public val learningWindow: Duration = Defaults.LearningWindow,
    public val doneChips: Duration = Defaults.DoneChips,
    public val commandUndo: Duration = Defaults.CommandUndo,
    public val problemDismiss: Duration = Defaults.ProblemDismiss,
    public val handoffLimit: Duration = Defaults.HandoffLimit,
    public val sessionWarningLead: Duration = Defaults.SessionWarningLead,
    public val teardown: Duration = Defaults.Device,
    public val inboxCapacity: Int = Defaults.INBOX_CAPACITY,
) {
    private object Defaults {
        val Device: Duration = 5.seconds
        val ContextRead: Duration = 1.seconds
        val Prepare: Duration = 10.seconds
        val Transcription: Duration = 120.seconds
        val ModelCeiling: Duration = 60.seconds
        val Insertion: Duration = 10.seconds
        val HealthProbe: Duration = 15.seconds
        val LearningWindow: Duration = 60.seconds
        val DoneChips: Duration = 5.seconds
        val CommandUndo: Duration = 10.seconds
        val ProblemDismiss: Duration = 5.seconds
        val HandoffLimit: Duration = 2.minutes
        val SessionWarningLead: Duration = 1.minutes
        const val INBOX_CAPACITY: Int = 64
    }
}
