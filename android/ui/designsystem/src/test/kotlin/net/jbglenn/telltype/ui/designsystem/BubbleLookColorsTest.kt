package net.jbglenn.telltype.ui.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.compositeOver
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleLookColorsTest {
    @Test
    fun theDefaultLookIsTheThemesReadyPair() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            assertEquals(scheme.bubbleColors(BubbleTone.READY), scheme.readyBubbleColors(BubbleLook.Default))
        }
    }

    @Test
    fun everyPresetAndEveryCustomColourKeepsTheGlyphAtLeastThreeToOne() {
        val colours = BubblePreset.entries.filter { it != BubblePreset.DEFAULT }.map { BubbleColour.Preset(it) } +
            (0..359 step 15).flatMap { hue -> (0..100 step 5).map { BubbleColour.Custom(hue, it) } }
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            for (transparency in listOf(0, 30, 80)) {
                colours.forEach { colour ->
                    val colors = scheme.readyBubbleColors(BubbleLook(colour = colour, transparency = transparency))
                    val seen = colors.container.copy(alpha = 1f - transparency / 100f).compositeOver(scheme.surface)
                    val ratio = contrast(seen, colors.content)
                    assertTrue("$colour at $transparency %: $ratio", ratio >= 3f)
                }
            }
        }
    }

    @Test
    fun todaysTransparencyDrawsTheWholeBubbleThroughOneLayerAsBefore() {
        assertEquals(ReadyLayers(layer = 0.7f, fill = 1f, shadow = true), ReadyLayers.of(30))
        assertEquals(ReadyLayers(layer = 1f, fill = 1f, shadow = true), ReadyLayers.of(0))
    }

    @Test
    fun moreTransparencyKeepsTheGlyphReadableAndDropsTheShadow() {
        val layers = ReadyLayers.of(80)
        assertEquals(0.7f, layers.layer, 0.001f)
        assertEquals("fill opacity overall 20 %", 0.2f, layers.layer * layers.fill, 0.001f)
        assertEquals(false, layers.shadow)
        assertEquals("clamped", layers, ReadyLayers.of(95))
    }
}
