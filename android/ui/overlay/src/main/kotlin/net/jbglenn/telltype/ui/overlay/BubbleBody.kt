package net.jbglenn.telltype.ui.overlay

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.MicIcon
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.BubbleTone
import net.jbglenn.telltype.ui.designsystem.PillShape
import net.jbglenn.telltype.ui.designsystem.ReadyLayers
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.TelltypeMotion
import net.jbglenn.telltype.ui.designsystem.bubbleColors
import net.jbglenn.telltype.ui.designsystem.drawable
import net.jbglenn.telltype.ui.designsystem.readyBubbleColors
import net.jbglenn.telltype.ui.overlay.gesture.detectBubblePress

/**
 * The circle or pill. Colour cross-fades between states, the drawn size follows a spring (a short tween with reduced
 * motion) from the docked [side] while the layout takes the new size at once ([SizeReveal]), and the content
 * cross-fades only when the kind of state changes (not on every timer tick), anchored to the docked side.
 *
 * The idle bubble takes the user's [look] (colour, transparency, glyph; [ReadyLayers]); every other state keeps its
 * state colour at full opacity, so the states stay apart whatever the user picks (decision D56).
 */
@Composable
internal fun BubbleBody(
    model: BubbleUiModel,
    side: BubbleSide,
    onEvent: (BubbleEvent) -> Unit,
    levels: WaveformLevels,
    motion: TelltypeMotion,
    modifier: Modifier = Modifier,
    look: BubbleLook = BubbleLook.Default,
) {
    val ready = model is BubbleUiModel.Ready
    val scheme = MaterialTheme.colorScheme
    val colors = if (ready) scheme.readyBubbleColors(look) else scheme.bubbleColors(model.tone)
    val container by animateColorAsState(colors.container, motion.fade(), label = "container")
    val content by animateColorAsState(colors.content, motion.fade(), label = "content")
    var pressed by remember { mutableStateOf(false) }
    val currentOnEvent by rememberUpdatedState(onEvent)
    val startsSession by rememberUpdatedState(model.pressStartsSession)
    val opensMenu by rememberUpdatedState(model.holdOpensMenu)
    val layers = if (ready && !pressed) ReadyLayers.of(look.transparency) else ReadyLayers.Opaque
    val circle = model.isCircle
    val fromEnd = side == BubbleSide.RIGHT
    val reveal = remember(motion) { SizeReveal(motion.resize()) }
    var laidOut by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(laidOut) { if (laidOut != IntSize.Zero) reveal.follow(laidOut) }
    Box(
        modifier = modifier
            .onSizeChanged { laidOut = it }
            .drawRevealed(reveal, fromEnd, ShadowMargin)
            .bubbleSemantics(model, onEvent)
            .pointerInput(Unit) {
                detectBubblePress(
                    startsSession = { startsSession },
                    holdOpensMenu = { opensMenu },
                    onPressedChange = { pressed = it },
                    onEvent = { currentOnEvent(it) },
                )
            },
    ) {
        Surface(
            shape = PillShape,
            color = container.copy(alpha = container.alpha * layers.fill),
            contentColor = content,
            shadowElevation = if (layers.shadow) BubbleTokens.Elevation else 0.dp,
            modifier = Modifier
                .padding(if (circle) CircleInset else 0.dp)
                .translucent(alpha = { layers.layer }, margin = ShadowMargin),
        ) {
            // No size transform: the new content's size applies at once (see SizeReveal), and while the old content
            // fades out the box keeps the larger of the two.
            AnimatedContent(
                targetState = model,
                contentKey = { it.kind },
                contentAlignment = if (fromEnd) Alignment.CenterEnd else Alignment.CenterStart,
                transitionSpec = { motion.crossFade() using null },
                label = "bubble",
            ) { target -> BodyContent(target, onEvent, levels, motion, look.icon) }
        }
    }
}

@Composable
private fun BodyContent(
    model: BubbleUiModel,
    onEvent: (BubbleEvent) -> Unit,
    levels: WaveformLevels,
    motion: TelltypeMotion,
    mic: MicIcon,
) {
    when (model) {
        BubbleUiModel.Hidden -> Unit
        is BubbleUiModel.Ready -> CircleContent(icon = model.mode.icon(mic), offline = model.offline)
        is BubbleUiModel.Unavailable -> CircleContent(icon = TelltypeIcons.MicOff, offline = false)
        is BubbleUiModel.Arming -> CircleContent(icon = model.mode.icon(mic), offline = false)
        is BubbleUiModel.Listening -> ListeningContent(model, onEvent, levels)
        is BubbleUiModel.Working -> WorkingContent(model, onEvent, motion)
        is BubbleUiModel.Done -> DoneContent(motion)
        is BubbleUiModel.Problem -> ProblemContent(model, onEvent)
    }
}

/** The colour role of each state (Appendix H); sliding away to cancel borrows the problem colours. */
internal val BubbleUiModel.tone: BubbleTone
    get() = when (this) {
        BubbleUiModel.Hidden, is BubbleUiModel.Ready -> BubbleTone.READY
        is BubbleUiModel.Unavailable -> BubbleTone.UNAVAILABLE
        is BubbleUiModel.Arming -> BubbleTone.LISTENING
        is BubbleUiModel.Listening -> if (releaseToCancel) BubbleTone.CANCELLING else BubbleTone.LISTENING
        is BubbleUiModel.Working -> BubbleTone.WORKING
        is BubbleUiModel.Done -> BubbleTone.DONE
        is BubbleUiModel.Problem -> BubbleTone.PROBLEM
    }

/**
 * A long press opens the bubble menu instead of hold-to-talk. Only the Unavailable bubble qualifies: everywhere a
 * press can start dictation (Ready, Done, Problem) a long press is hold-to-talk, so it never opens the menu there.
 */
internal val BubbleUiModel.holdOpensMenu: Boolean
    get() = this is BubbleUiModel.Unavailable

/** A press here starts a session, so a drag must discard the recording the press opened. */
internal val BubbleUiModel.pressStartsSession: Boolean
    get() = this is BubbleUiModel.Ready || this is BubbleUiModel.Done || this is BubbleUiModel.Problem

private val BubbleUiModel.isCircle: Boolean
    get() = this is BubbleUiModel.Ready ||
        this is BubbleUiModel.Unavailable ||
        this is BubbleUiModel.Arming ||
        this is BubbleUiModel.Done

/** Which layout a model uses; the content cross-fades only when this changes. */
private val BubbleUiModel.kind: Int
    get() = when (this) {
        BubbleUiModel.Hidden -> 0
        is BubbleUiModel.Ready, is BubbleUiModel.Unavailable, is BubbleUiModel.Arming -> 1
        is BubbleUiModel.Listening -> 2
        is BubbleUiModel.Working -> 3
        is BubbleUiModel.Done -> 4
        is BubbleUiModel.Problem -> 5
    }

/** The glyph of this mode: the chosen microphone [mic] for dictation, the wand for Command Mode. */
@DrawableRes
internal fun BubbleMode.icon(mic: MicIcon): Int = when (this) {
    BubbleMode.DICTATE -> mic.drawable
    BubbleMode.COMMAND -> TelltypeIcons.Wand
}

/**
 * Draws this element with [alpha] through a layer that reaches [margin] beyond its bounds, without changing its
 * size. A layer the size of the element would cut the shadow off at its square edges, turning the 44 dp circle
 * into a rounded square; drawing each part at [alpha] instead would let the shadow show through the fill.
 */
private fun Modifier.translucent(alpha: () -> Float, margin: Dp): Modifier = this
    .layout { measurable, constraints ->
        val extra = margin.roundToPx()
        val placeable = measurable.measure(constraints.offset(2 * extra, 2 * extra))
        layout(placeable.width - 2 * extra, placeable.height - 2 * extra) { placeable.place(-extra, -extra) }
    }
    .graphicsLayer { this.alpha = alpha() }
    .padding(margin)

/** Room around the bubble for its level 3 shadow. */
private val ShadowMargin = BubbleTokens.Elevation * 2

/** The Ready circle is 44 dp inside its 48 dp touch target (Appendix H). */
private val CircleInset = (BubbleTokens.TouchTarget - BubbleTokens.ReadySize) / 2
