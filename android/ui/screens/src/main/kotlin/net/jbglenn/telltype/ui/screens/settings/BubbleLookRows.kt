package net.jbglenn.telltype.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import net.jbglenn.telltype.core.model.settings.MicIcon
import net.jbglenn.telltype.ui.designsystem.BubbleTokens
import net.jbglenn.telltype.ui.designsystem.Layout
import net.jbglenn.telltype.ui.designsystem.ReadyLayers
import net.jbglenn.telltype.ui.designsystem.Spacing
import net.jbglenn.telltype.ui.designsystem.TelltypeIcons
import net.jbglenn.telltype.ui.designsystem.drawable
import net.jbglenn.telltype.ui.designsystem.fill
import net.jbglenn.telltype.ui.designsystem.readyBubbleColors
import net.jbglenn.telltype.ui.screens.R

/**
 * The idle bubble's look in Settings → Bubble (board U9, decision D56): a live preview of the Ready bubble, the colour
 * (eight presets and a custom hue and lightness), the transparency (0 to 80 %) and the microphone glyph. Sliders move
 * the preview at once and save when the finger lifts. Only the Ready bubble changes; the other states keep their
 * colours, as the supporting text says.
 */
@Composable
internal fun BubbleLookRows(look: BubbleLook, onChange: (BubbleLook) -> Unit) {
    var draft by remember(look) { mutableStateOf(look) }
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Large, vertical = Spacing.Small),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Large),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_bubble_look), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_bubble_look_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ReadyBubblePreview(draft)
        }
        ColourChoice(draft) { onChange(draft.copy(colour = it)) }
        (draft.colour as? BubbleColour.Custom)?.let { custom ->
            LookSlider(
                SliderSpec(R.string.settings_bubble_hue, 0..LookRanges.MAX_HUE, steps = 0),
                custom.hue,
                valueText = stringResource(R.string.settings_degrees, custom.hue),
                onRelease = { onChange(draft) },
            ) { draft = draft.copy(colour = custom.copy(hue = it)) }
            LookSlider(
                SliderSpec(
                    R.string.settings_bubble_lightness,
                    LookRanges.MIN_LIGHTNESS..LookRanges.MAX_LIGHTNESS,
                    steps = 0,
                ),
                custom.lightness,
                valueText = stringResource(R.string.settings_percent, custom.lightness),
                onRelease = { onChange(draft) },
            ) { draft = draft.copy(colour = custom.copy(lightness = it)) }
        }
        LookSlider(
            SliderSpec(
                R.string.settings_bubble_transparency,
                0..BubbleLook.MAX_TRANSPARENCY,
                steps = BubbleLook.MAX_TRANSPARENCY / LookRanges.TRANSPARENCY_STEP - 1,
            ),
            draft.transparency,
            valueText = stringResource(R.string.settings_percent, draft.transparency),
            onRelease = { onChange(draft) },
        ) { draft = draft.copy(transparency = it) }
        IconChoice(draft.icon) { onChange(draft.copy(icon = it)) }
    }
}

/** The Ready bubble as it will look, on a striped backdrop so that its transparency shows. */
@Composable
private fun ReadyBubblePreview(look: BubbleLook) {
    val colors = MaterialTheme.colorScheme.readyBubbleColors(look)
    val layers = ReadyLayers.of(look.transparency)
    val description = stringResource(R.string.settings_bubble_preview)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(PreviewBackdrop)
            .background(Brush.linearGradient(listOf(Color.Black, Color.White)), CircleShape)
            .semantics { contentDescription = description },
    ) {
        Surface(
            shape = CircleShape,
            color = colors.container.copy(alpha = layers.fill),
            contentColor = colors.content,
            shadowElevation = if (layers.shadow) BubbleTokens.Elevation else 0.dp,
            modifier = Modifier
                .size(BubbleTokens.ReadySize)
                .alpha(layers.layer),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(look.icon.drawable), contentDescription = null)
            }
        }
    }
}

/** The colour swatches: the presets, then Custom (which opens the hue and lightness sliders). */
@OptIn(ExperimentalLayoutApi::class) // FlowRow: the swatches wrap at large font sizes and on narrow screens.
@Composable
private fun ColourChoice(look: BubbleLook, onSelect: (BubbleColour) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
        Text(stringResource(R.string.settings_bubble_colour), style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Small),
            verticalArrangement = Arrangement.spacedBy(Spacing.Small),
        ) {
            BubblePreset.entries.forEach { preset ->
                val colour = BubbleColour.Preset(preset)
                val fill = MaterialTheme.colorScheme.readyBubbleColors(BubbleLook(colour = colour)).container
                val brush = Brush.linearGradient(listOf(fill, fill))
                Swatch(stringResource(preset.label), look.colour == colour, brush) { onSelect(colour) }
            }
            val custom = look.colour as? BubbleColour.Custom
            Swatch(stringResource(R.string.settings_bubble_colour_custom), custom != null, HueBrush) {
                onSelect(custom ?: DefaultCustom)
            }
        }
    }
}

/** One round swatch, 48 dp to touch, with a thicker ring in the primary colour when selected. */
@Composable
private fun Swatch(label: String, selected: Boolean, brush: Brush, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Layout.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(SwatchSize)
                .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), CircleShape)
                .padding(if (selected) 5.dp else 2.dp)
                .background(brush, CircleShape),
        )
    }
}

/** The microphone glyphs, each drawn the way the bubble draws it. */
@Composable
private fun IconChoice(selected: MicIcon, onSelect: (MicIcon) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.ExtraSmall)) {
        Text(stringResource(R.string.settings_bubble_icon), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
            MicIcon.entries.forEach { icon ->
                val chosen = icon == selected
                val label = stringResource(icon.label)
                Surface(
                    shape = CircleShape,
                    color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = if (chosen) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                    modifier = Modifier
                        .size(Layout.MinTouchTarget)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(icon) })
                        .semantics { contentDescription = label },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(icon.drawable), contentDescription = null)
                    }
                }
            }
        }
    }
}

/**
 * What a [LookSlider] adjusts.
 *
 * @property title the label's string resource.
 * @property range the whole numbers it can take.
 * @property steps the discrete stops between the ends (0: continuous).
 */
private data class SliderSpec(val title: Int, val range: IntRange, val steps: Int)

/**
 * A labelled slider over whole numbers, with its value at the end of the label line: [onValueChange] while it moves
 * (the preview follows), [onRelease] when it is let go (the look is saved).
 */
@Composable
private fun LookSlider(
    spec: SliderSpec,
    value: Int,
    valueText: String,
    onRelease: () -> Unit,
    onValueChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(spec.title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            onValueChangeFinished = onRelease,
            valueRange = spec.range.first.toFloat()..spec.range.last.toFloat(),
            steps = spec.steps,
        )
    }
}

private val BubblePreset.label: Int
    get() = when (this) {
        BubblePreset.DEFAULT -> R.string.settings_bubble_colour_default
        BubblePreset.GRAPHITE -> R.string.settings_bubble_colour_graphite
        BubblePreset.BLUE -> R.string.settings_bubble_colour_blue
        BubblePreset.TEAL -> R.string.settings_bubble_colour_teal
        BubblePreset.GREEN -> R.string.settings_bubble_colour_green
        BubblePreset.VIOLET -> R.string.settings_bubble_colour_violet
        BubblePreset.ROSE -> R.string.settings_bubble_colour_rose
        BubblePreset.SAND -> R.string.settings_bubble_colour_sand
    }

private val MicIcon.label: Int
    get() = when (this) {
        MicIcon.STANDARD -> R.string.settings_bubble_icon_standard
        MicIcon.BOLD -> R.string.settings_bubble_icon_bold
        MicIcon.RING -> R.string.settings_bubble_icon_ring
        MicIcon.WAVE -> R.string.settings_bubble_icon_wave
    }

/** The colour Custom starts from: a mid blue. */
private val DefaultCustom = BubbleColour.Custom(hue = 220, lightness = 50)

/** The Custom swatch: the hues around the wheel. */
private val HueBrush = Brush.sweepGradient((0..360 step 60).map { BubbleColour.Custom(it % 360, 50).fill() })

private val SwatchSize = 40.dp
private val PreviewBackdrop = 64.dp

/** The ranges of the look's sliders. */
private object LookRanges {
    const val MAX_HUE = 359
    const val MIN_LIGHTNESS = 10
    const val MAX_LIGHTNESS = 90
    const val TRANSPARENCY_STEP = 10
}
