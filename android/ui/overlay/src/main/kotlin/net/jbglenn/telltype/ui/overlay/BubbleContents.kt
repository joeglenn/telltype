package net.jbglenn.telltype.ui.overlay

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.time.Duration
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.tabularNumbers

/** Ready, Unavailable and Arming: one icon in the circle, with the offline mark when the server is unreachable. */
@Composable
internal fun CircleContent(@DrawableRes icon: Int, offline: Boolean, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(BubbleTokens.ReadySize)) {
        Icon(painter = painterResource(icon), contentDescription = null)
        if (offline) {
            OfflineMark(modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** A small cloud-off glyph on a contrasting disc: the shape carries the meaning, not only a colour. */
@Composable
private fun OfflineMark(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = modifier.size(BubbleTokens.OfflineMarkSize),
    ) {
        Icon(
            painter = painterResource(TelltypeIcons.CloudOff),
            contentDescription = null,
            modifier = Modifier.padding(OfflineGlyphPadding),
        )
    }
}

/** ✕, the live waveform (or "Release to cancel"), the timer after 10 s, ✓. */
@Composable
internal fun ListeningContent(
    model: BubbleUiModel.Listening,
    onEvent: (BubbleEvent) -> Unit,
    levels: WaveformLevels,
    modifier: Modifier = Modifier,
) {
    PillRow(modifier = modifier) {
        PillIconButton(
            icon = TelltypeIcons.Close,
            label = stringResource(R.string.bubble_cancel),
            onClick = { onEvent(BubbleEvent.Session(SessionEvent.CancelTapped)) },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = Spacing.Small),
        ) {
            if (model.releaseToCancel) {
                Text(
                    text = stringResource(R.string.bubble_release_to_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (model.mode == BubbleMode.COMMAND) {
                        Icon(
                            painter = painterResource(TelltypeIcons.Wand),
                            contentDescription = null,
                            modifier = Modifier.size(SmallIcon),
                        )
                        Spacer(modifier = Modifier.width(Spacing.ExtraSmall))
                    }
                    Waveform(
                        levels = levels,
                        color = LocalContentColor.current,
                        modifier = Modifier
                            .width(WaveformWidth)
                            .height(WaveformHeight),
                    )
                }
            }
        }
        model.elapsed?.let { Timer(elapsed = it) }
        if (model.offline) {
            Icon(
                painter = painterResource(TelltypeIcons.CloudOff),
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = Spacing.ExtraSmall)
                    .size(SmallIcon),
            )
        }
        PillIconButton(
            icon = TelltypeIcons.Check,
            label = stringResource(R.string.bubble_finish),
            onClick = { onEvent(BubbleEvent.Session(SessionEvent.FinishTapped)) },
        )
    }
}

@Composable
private fun Timer(elapsed: Duration, modifier: Modifier = Modifier) {
    val minutes = elapsed.inWholeMinutes
    val seconds = elapsed.inWholeSeconds % BubbleContentDefaults.SECONDS_PER_MINUTE
    Text(
        text = stringResource(R.string.bubble_timer, minutes, seconds),
        style = MaterialTheme.typography.labelLarge.tabularNumbers(),
        modifier = modifier.padding(horizontal = Spacing.ExtraSmall),
    )
}

/** The shimmer, the stage label after 1.5 s, and ✕ to cancel. */
@Composable
internal fun WorkingContent(
    model: BubbleUiModel.Working,
    onEvent: (BubbleEvent) -> Unit,
    motion: TelltypeMotion,
    modifier: Modifier = Modifier,
) {
    PillRow(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small, Alignment.CenterHorizontally),
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = Spacing.Large, end = Spacing.Small),
        ) {
            WorkingIndicator(
                color = LocalContentColor.current,
                motion = motion,
                modifier = Modifier
                    .width(if (model.label == null) WaveformWidth else ShortWaveformWidth)
                    .height(WaveformHeight),
            )
            model.label?.let {
                Text(
                    text = stringResource(it.text),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PillIconButton(
            icon = TelltypeIcons.Close,
            label = stringResource(R.string.bubble_cancel),
            onClick = { onEvent(BubbleEvent.Session(SessionEvent.CancelTapped)) },
        )
    }
}

/** The check mark, drawn in over 300 ms (100 ms with reduced motion). */
@Composable
internal fun DoneContent(motion: TelltypeMotion, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) { progress.animateTo(1f, motion.check()) }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(BubbleTokens.ReadySize)) {
        Icon(
            painter = painterResource(TelltypeIcons.Check),
            contentDescription = null,
            modifier = Modifier.graphicsLayer {
                alpha = progress.value
                scaleX =
                    BubbleContentDefaults.CHECK_START_SCALE +
                    (1f - BubbleContentDefaults.CHECK_START_SCALE) * progress.value
                scaleY = scaleX
            },
        )
    }
}

/** The error icon, the message, and ✕ to dismiss. Retry and Fix are chips above. */
@Composable
internal fun ProblemContent(
    model: BubbleUiModel.Problem,
    onEvent: (BubbleEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    PillRow(modifier = modifier) {
        Icon(
            painter = painterResource(TelltypeIcons.Warning),
            contentDescription = null,
            modifier = Modifier
                .padding(start = Spacing.Medium)
                .size(SmallIcon),
        )
        Text(
            text = stringResource(model.message),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = Spacing.Small, vertical = Spacing.Small),
        )
        if (BubbleAction.DISMISS in model.actions) {
            PillIconButton(
                icon = TelltypeIcons.Close,
                label = stringResource(R.string.bubble_action_dismiss),
                onClick = { onEvent(BubbleEvent.Session(SessionEvent.ActionTapped(BubbleAction.DISMISS))) },
            )
        } else {
            Spacer(modifier = Modifier.width(Spacing.Medium))
        }
    }
}

@Composable
private fun PillRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .widthIn(min = BubbleTokens.PillWidth)
            .heightIn(min = BubbleTokens.PillHeight),
        content = content,
    )
}

@Composable
private fun PillIconButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val exposed = rememberExposedAtRest()
    IconButton(onClick = onClick, modifier = modifier.exposedWhen(exposed).size(BubbleTokens.TouchTarget)) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

private val SmallIcon = 18.dp
private val OfflineGlyphPadding = 2.dp
private val WaveformWidth = 56.dp
private val ShortWaveformWidth = 36.dp
private val WaveformHeight = 24.dp

/** Fixed values used in this file. */
private object BubbleContentDefaults {
    const val SECONDS_PER_MINUTE = 60
    const val CHECK_START_SCALE = 0.6f
}
