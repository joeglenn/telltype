package net.jbglenn.telltype.data.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import net.jbglenn.telltype.core.model.settings.BubbleColour
import net.jbglenn.telltype.core.model.settings.BubbleLook
import net.jbglenn.telltype.core.model.settings.BubblePreset
import net.jbglenn.telltype.core.model.settings.MicIcon
import net.jbglenn.telltype.core.model.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleLookCodecTest {
    private fun roundTrip(look: BubbleLook): Pair<BubbleLook, List<String>> {
        val settings = Settings.Defaults.copy(bubble = Settings.Defaults.bubble.copy(look = look))
        val preferences = mutablePreferencesOf()
        SettingsCodec.encode(settings, preferences)
        val values = preferences.asMap().mapKeys { it.key.name }
        val (decoded, problems) = SettingsCodec.decode(values, key = null)
        return decoded.bubble.look to problems
    }

    @Test
    fun `settings stored before the look existed read as today's look`() {
        val stored = mapOf<String, Any?>("bubble.enabled" to true, "bubble.side" to "LEFT")

        val (settings, problems) = SettingsCodec.decode(stored, key = null)

        assertEquals(BubbleLook.Default, settings.bubble.look)
        assertEquals(BubbleColour.Preset(BubblePreset.DEFAULT), settings.bubble.look.colour)
        assertEquals(30, settings.bubble.look.transparency)
        assertEquals(MicIcon.STANDARD, settings.bubble.look.icon)
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `a preset and a custom colour round-trip`() {
        val preset = BubbleLook(BubbleColour.Preset(BubblePreset.TEAL), transparency = 0, icon = MicIcon.RING)
        val custom = BubbleLook(BubbleColour.Custom(hue = 300, lightness = 35), transparency = 80, icon = MicIcon.WAVE)

        assertEquals(preset to emptyList<String>(), roundTrip(preset))
        assertEquals(custom to emptyList<String>(), roundTrip(custom))
    }

    @Test
    fun `switching from custom back to a preset forgets the hue and lightness`() {
        val preferences = mutablePreferencesOf()
        BubbleLookCodec.encode(BubbleLook(BubbleColour.Custom(10, 20)), preferences)
        BubbleLookCodec.encode(BubbleLook(BubbleColour.Preset(BubblePreset.BLUE)), preferences)

        assertEquals(null, preferences[intPreferencesKey("bubble.look.hue")])
        assertEquals("BLUE", preferences[stringPreferencesKey("bubble.look.colour")])
    }

    @Test
    fun `invalid values fall back to their defaults and are reported by name`() {
        val problems = mutableListOf<String>()
        val stored = mapOf<String, Any?>(
            "bubble.look.colour" to "PLAID",
            "bubble.look.transparency" to 95,
            "bubble.look.icon" to 3,
        )

        assertEquals(BubbleLook.Default, BubbleLookCodec.decode(stored, problems))
        assertEquals(listOf("bubble.look.colour", "bubble.look.icon", "bubble.look.transparency"), problems.sorted())
    }

    @Test
    fun `a custom colour with a broken hue keeps its lightness`() {
        val problems = mutableListOf<String>()
        val stored = mapOf<String, Any?>(
            "bubble.look.colour" to "CUSTOM",
            "bubble.look.hue" to 400,
            "bubble.look.lightness" to 70,
        )

        assertEquals(BubbleColour.Custom(hue = 220, lightness = 70), BubbleLookCodec.decode(stored, problems).colour)
        assertEquals(listOf("bubble.look.hue"), problems)
    }
}
