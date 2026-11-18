package net.jbglenn.telltype.ui.designsystem

import androidx.annotation.DrawableRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import net.jbglenn.telltype.core.model.settings.MicIcon

/**
 * The Ready bubble's colours for the user's [look] (board U9, decision D56). The default preset is the theme's own
 * pair (surface container high, on surface), as before. Every other colour gets the glyph colour of higher contrast,
 * near-black or white ([contentFor]), judged against the fill as it is seen at the chosen transparency over the
 * theme's surface (a see-through dark fill looks light on a light screen): one of the two always reaches at least
 * 4.1:1 against that colour, above the 3:1 WCAG asks of non-text graphics. What an app draws behind the bubble is not
 * known; the glyph keeps at least 70 % opacity ([ReadyLayers]).
 */
fun ColorScheme.readyBubbleColors(look: BubbleLook): BubbleColors {
    val colour = look.colour
    return if (colour == BubbleColour.Preset(BubblePreset.DEFAULT)) {
        bubbleColors(BubbleTone.READY)
    } else {
        val container = colour.fill()
        val opacity = 1f - look.transparency.coerceIn(0, BubbleLook.MAX_TRANSPARENCY) / LookMath.PERCENT.toFloat()
        BubbleColors(container, contentFor(container.copy(alpha = opacity).compositeOver(surface)))
    }
}

/** The fill of a colour other than the theme's: the preset's, or the custom hue and lightness. */
fun BubbleColour.fill(): Color = when (this) {
    is BubbleColour.Preset -> PresetFills.getValue(preset)

    is BubbleColour.Custom -> Color.hsl(
        hue = hue.coerceIn(0, LookMath.MAX_HUE).toFloat(),
        saturation = LookMath.CUSTOM_SATURATION,
        lightness = lightness.coerceIn(0, LookMath.PERCENT) / LookMath.PERCENT.toFloat(),
    )
}

/** The glyph colour for [container]: near-black or white, whichever contrasts more (WCAG contrast ratio). */
fun contentFor(container: Color): Color =
    if (contrast(container, GlyphDark) >= contrast(container, Color.White)) GlyphDark else Color.White

/** The WCAG contrast ratio of two opaque colours, `1..21`. */
fun contrast(a: Color, b: Color): Float {
    val lighter = maxOf(a.luminance(), b.luminance())
    val darker = minOf(a.luminance(), b.luminance())
    return (lighter + LookMath.CONTRAST_OFFSET) / (darker + LookMath.CONTRAST_OFFSET)
}

/** The drawable of each microphone glyph style. */
@get:DrawableRes
val MicIcon.drawable: Int
    get() = when (this) {
        MicIcon.STANDARD -> TelltypeIcons.Mic
        MicIcon.BOLD -> TelltypeIcons.MicBold
        MicIcon.RING -> TelltypeIcons.MicRing
        MicIcon.WAVE -> TelltypeIcons.MicWave
    }

/**
 * How the Ready bubble is drawn translucent for a [transparency] in percent: up to today's 30 % the whole bubble
 * (fill, glyph and shadow) is drawn through one layer at that opacity, exactly as before. Beyond it the glyph stays
 * at 70 % so it still reads, only the fill becomes more see-through, and the shadow is left out (it would show
 * through the fill as a grey blot).
 *
 * @property layer the opacity of the whole bubble.
 * @property fill the extra opacity of the fill inside that layer.
 * @property shadow whether the bubble casts its shadow.
 */
data class ReadyLayers(val layer: Float, val fill: Float, val shadow: Boolean) {
    companion object {
        /** A bubble under the finger, and every state other than Ready: opaque, with its shadow. */
        val Opaque: ReadyLayers = ReadyLayers(layer = 1f, fill = 1f, shadow = true)

        /** The layers for [transparency] percent (clamped to `0..`[BubbleLook.MAX_TRANSPARENCY]). */
        fun of(transparency: Int): ReadyLayers {
            val opacity = 1f - transparency.coerceIn(0, BubbleLook.MAX_TRANSPARENCY) / LookMath.PERCENT.toFloat()
            return if (opacity >= BubbleTokens.READY_OPACITY - LookMath.EPSILON) {
                ReadyLayers(layer = opacity, fill = 1f, shadow = true)
            } else {
                val glyph = BubbleTokens.READY_OPACITY
                ReadyLayers(layer = glyph, fill = opacity / glyph, shadow = false)
            }
        }
    }
}

/** The preset fills; the same in both themes, each with its own glyph colour from [contentFor]. */
private val PresetFills: Map<BubblePreset, Color> = mapOf(
    BubblePreset.DEFAULT to Color(0xFFE6E8EE),
    BubblePreset.GRAPHITE to Color(0xFF3C4043),
    BubblePreset.BLUE to Color(0xFF3D6FD8),
    BubblePreset.TEAL to Color(0xFF00897B),
    BubblePreset.GREEN to Color(0xFF4C9A52),
    BubblePreset.VIOLET to Color(0xFF7E57C2),
    BubblePreset.ROSE to Color(0xFFD5487F),
    BubblePreset.SAND to Color(0xFFD9C7A5),
)

/** The near-black glyph colour. */
private val GlyphDark = Color(0xFF1A1C1E)

/** The numbers of the look's colour arithmetic. */
private object LookMath {
    const val MAX_HUE = 359
    const val PERCENT = 100
    const val CUSTOM_SATURATION = 0.55f
    const val CONTRAST_OFFSET = 0.05f
    const val EPSILON = 0.001f
}
