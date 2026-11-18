package net.jbglenn.telltype.ui.overlay

import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.data.Correction
import net.jbglenn.telltype.core.model.error.MicrophonePermissionMissing
import net.jbglenn.telltype.core.model.error.ServerWarmingUp
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.MicIcon
import net.jbglenn.telltype.ui.designsystem.R as DesignR
import net.jbglenn.telltype.ui.designsystem.error.messageRes

/** Every bubble state and chip, by name, for the screenshot and UI tests. */
internal object BubbleScenes {
    private fun scene(bubble: BubbleUiModel, chip: ChipUiModel? = null, side: BubbleSide = BubbleSide.RIGHT) =
        BubbleScene(bubble, chip, side)

    /** The Ready bubble with a [look] of the user's (board U9). */
    private fun looked(look: BubbleLook) = BubbleScene(
        BubbleUiModel.Ready(BubbleMode.DICTATE, offline = false),
        chip = null,
        side = BubbleSide.RIGHT,
        look = look,
    )

    private val listening = BubbleUiModel.Listening(
        mode = BubbleMode.DICTATE,
        hold = false,
        releaseToCancel = false,
        elapsed = null,
        offline = false,
    )

    val all: Map<String, BubbleScene> = linkedMapOf(
        "ready" to scene(BubbleUiModel.Ready(BubbleMode.DICTATE, offline = false)),
        "readyOffline" to scene(BubbleUiModel.Ready(BubbleMode.DICTATE, offline = true)),
        "readyCommand" to scene(BubbleUiModel.Ready(BubbleMode.COMMAND, offline = false)),
        "readyLeft" to scene(BubbleUiModel.Ready(BubbleMode.DICTATE, offline = false), side = BubbleSide.LEFT),
        "readyLookTealRing" to looked(BubbleLook(BubbleColour.Preset(BubblePreset.TEAL), 0, MicIcon.RING)),
        "readyLookSandBold" to looked(BubbleLook(BubbleColour.Preset(BubblePreset.SAND), 30, MicIcon.BOLD)),
        "readyLookCustomWave" to looked(BubbleLook(BubbleColour.Custom(hue = 300, lightness = 35), 70, MicIcon.WAVE)),
        "listeningWithACustomLook" to BubbleScene(
            listening,
            chip = null,
            side = BubbleSide.RIGHT,
            look = BubbleLook(BubbleColour.Custom(hue = 300, lightness = 35), 70, MicIcon.WAVE),
        ),
        "unavailable" to scene(BubbleUiModel.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING)),
        "menu" to BubbleScene(
            BubbleUiModel.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
            chip = null,
            side = BubbleSide.RIGHT,
            menu = true,
        ),
        "arming" to scene(BubbleUiModel.Arming(BubbleMode.DICTATE)),
        "listening" to scene(listening),
        "listeningTimer" to scene(listening.copy(elapsed = 74.seconds)),
        "listeningOffline" to scene(listening.copy(offline = true)),
        "listeningCommand" to scene(listening.copy(mode = BubbleMode.COMMAND)),
        "holdToTalk" to scene(listening.copy(hold = true)),
        "holdReleaseToCancel" to scene(listening.copy(hold = true, releaseToCancel = true)),
        "working" to scene(BubbleUiModel.Working(label = null)),
        "workingTranscribing" to scene(BubbleUiModel.Working(WorkingLabel.TRANSCRIBING)),
        "workingPolishing" to scene(BubbleUiModel.Working(WorkingLabel.POLISHING)),
        "doneCheck" to scene(BubbleUiModel.Done(chips = emptyList())),
        "doneRawUndo" to scene(BubbleUiModel.Done(listOf(BubbleAction.RAW, BubbleAction.UNDO))),
        "donePaste" to scene(BubbleUiModel.Done(listOf(BubbleAction.PASTE))),
        "doneCopyLeft" to scene(BubbleUiModel.Done(listOf(BubbleAction.COPY)), side = BubbleSide.LEFT),
        "problemRetry" to scene(
            BubbleUiModel.Problem(ServerWarmingUp.messageRes(), listOf(BubbleAction.RETRY, BubbleAction.DISMISS)),
        ),
        "problemFix" to scene(
            BubbleUiModel.Problem(
                MicrophonePermissionMissing.messageRes(),
                listOf(BubbleAction.FIX, BubbleAction.DISMISS),
            ),
        ),
        "problemLongMessage" to scene(
            BubbleUiModel.Problem(
                DesignR.string.error_insert_not_confirmed,
                listOf(BubbleAction.RETRY, BubbleAction.FIX, BubbleAction.DISMISS),
            ),
        ),
        "chipBusy" to scene(BubbleUiModel.Working(null), chip = ChipUiModel.Busy),
        "chipEndingSoon" to scene(listening.copy(elapsed = 9.seconds * 60), chip = ChipUiModel.SessionEndingSoon),
        "chipExplanation" to scene(
            BubbleUiModel.Unavailable(UnavailableReason.SERVER_NOT_CONFIGURED),
            chip = ChipUiModel.Explanation(UnavailableReason.SERVER_NOT_CONFIGURED),
        ),
        "chipExplanationMicrophone" to scene(
            BubbleUiModel.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
            chip = ChipUiModel.Explanation(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
        ),
        "chipExplanationMicrophoneLeft" to scene(
            BubbleUiModel.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
            chip = ChipUiModel.Explanation(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
            side = BubbleSide.LEFT,
        ),
        "chipLearnOffer" to scene(
            BubbleUiModel.Done(listOf(BubbleAction.UNDO)),
            chip = ChipUiModel.LearnOffer(Correction(heard = "cube control", replaceWith = "kubectl")),
        ),
    )
}
