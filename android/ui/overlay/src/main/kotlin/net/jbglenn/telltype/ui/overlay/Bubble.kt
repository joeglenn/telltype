package net.jbglenn.telltype.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.TelltypePreviewTheme
import net.jbglenn.telltype.ui.designsystem.rememberTelltypeMotion

/**
 * The floating dictation bubble (docs/PLAN.md section 5.2, Appendix H). Stateless: it draws [scene] and reports
 * every gesture and chip through [onEvent]. From the screen edge outwards it stacks the pill (nearest the
 * keyboard), the action chips, the long-press menu and the transient chip, aligned to the docking side.
 *
 * Gestures: a tap starts or finishes; a press held past the hold threshold is hold-to-talk wherever a press can
 * start dictation, and opens the menu ([BubbleEvent.MenuRequested]) only on the Unavailable bubble; a press that
 * moves before the threshold drags the bubble (onto the snooze tray to hide it).
 *
 * @param levels the live input levels for the waveform (see [rememberWaveformLevels]).
 * @param motion normal or reduced motion.
 */
@Composable
fun Bubble(
    scene: BubbleScene,
    onEvent: (BubbleEvent) -> Unit,
    modifier: Modifier = Modifier,
    levels: WaveformLevels = remember { WaveformLevels() },
    motion: TelltypeMotion = rememberTelltypeMotion(),
) {
    val alignment = if (scene.side == BubbleSide.RIGHT) Alignment.End else Alignment.Start
    val chipActions = scene.bubble.chipActions()
    Column(
        horizontalAlignment = alignment,
        verticalArrangement = Arrangement.spacedBy(BubbleTokens.ChipGap),
        modifier = modifier
            .onSizeChanged { onEvent(BubbleEvent.Resized(it.width, it.height)) }
            .padding(Spacing.Small)
            .widthIn(max = BubbleTokens.MaxWidth),
    ) {
        scene.chip?.let { TransientChip(chip = it, onEvent = onEvent) }
        if (scene.menu) BubbleMenu(onEvent = onEvent)
        if (chipActions.isNotEmpty()) {
            ActionChipRow(actions = BubbleActions(chipActions), alignment = alignment, onEvent = onEvent)
        }
        if (scene.bubble != BubbleUiModel.Hidden) {
            BubbleBody(
                model = scene.bubble,
                side = scene.side,
                onEvent = onEvent,
                levels = levels,
                motion = motion,
                look = scene.look,
            )
        }
    }
}

/** The actions drawn as chips above the pill: Done's chips, and Problem's actions except Dismiss. */
internal fun BubbleUiModel.chipActions(): List<BubbleAction> = when (this) {
    is BubbleUiModel.Done -> chips
    is BubbleUiModel.Problem -> actions.filter { it != BubbleAction.DISMISS }
    else -> emptyList()
}

@Preview
@Composable
private fun BubbleListeningPreview() {
    TelltypePreviewTheme {
        Bubble(
            scene = BubbleScene(
                bubble = BubbleUiModel.Listening(BubbleMode.DICTATE, false, false, null, false),
                chip = null,
                side = BubbleSide.RIGHT,
            ),
            onEvent = {},
            motion = TelltypeMotion(reduced = false),
        )
    }
}
