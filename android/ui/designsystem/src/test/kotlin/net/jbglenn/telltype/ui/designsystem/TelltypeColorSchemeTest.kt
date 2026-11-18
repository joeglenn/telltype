package net.jbglenn.telltype.ui.designsystem

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelltypeColorSchemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun usesFallbackPaletteWhenDynamicColourIsOff() {
        assertEquals(TelltypeLightColors, telltypeColorScheme(context, darkTheme = false, dynamicColor = false))
        assertEquals(TelltypeDarkColors, telltypeColorScheme(context, darkTheme = true, dynamicColor = false))
    }

    @Test
    fun usesDynamicPaletteWhenDynamicColourIsOn() {
        val dynamicLight = telltypeColorScheme(context, darkTheme = false, dynamicColor = true)
        val dynamicDark = telltypeColorScheme(context, darkTheme = true, dynamicColor = true)

        assertNotEquals(TelltypeLightColors, dynamicLight)
        assertNotEquals(dynamicLight.surface, dynamicDark.surface)
    }

    @Test
    fun listeningUsesPrimaryAndWorkingSecondaryContainer() {
        listOf(TelltypeLightColors, TelltypeDarkColors).forEach { scheme ->
            assertEquals(
                BubbleColors(scheme.primaryContainer, scheme.onPrimaryContainer),
                scheme.bubbleColors(BubbleTone.LISTENING),
            )
            assertEquals(
                BubbleColors(scheme.secondaryContainer, scheme.onSecondaryContainer),
                scheme.bubbleColors(BubbleTone.WORKING),
            )
        }
    }

    @Test
    fun fallbackListeningAndWorkingContainersAreVisiblyDifferent() {
        listOf("light" to TelltypeLightColors, "dark" to TelltypeDarkColors).forEach { (name, scheme) ->
            val primary = scheme.primaryContainer
            val secondary = scheme.secondaryContainer
            val distance = abs(primary.red - secondary.red) +
                abs(primary.green - secondary.green) +
                abs(primary.blue - secondary.blue)
            assertTrue("$name containers differ by $distance", distance >= MIN_CHANNEL_DISTANCE)
        }
    }

    private companion object {
        /** About 25 of 255 summed over the channels: a difference people see side by side. */
        const val MIN_CHANNEL_DISTANCE = 0.1f
    }
}
