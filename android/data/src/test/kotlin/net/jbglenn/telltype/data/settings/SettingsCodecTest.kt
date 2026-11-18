package net.jbglenn.telltype.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.settings.AdvancedSettings
import net.jbglenn.telltype.core.model.settings.BubbleSettings
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.CleanupSettings
import net.jbglenn.telltype.core.model.settings.ContextSettings
import net.jbglenn.telltype.core.model.settings.DictationSettings
import net.jbglenn.telltype.core.model.settings.DictionarySettings
import net.jbglenn.telltype.core.model.settings.InsertionSettings
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.core.model.settings.PrivacySettings
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    /** Every setting changed away from its default. */
    private val everythingChanged = Settings(
        server = ServerConfig(
            address = ServerEndpoint("fd00::1", 8443),
            key = ServerKey("synthetic-key"),
            certificate = CertificateFingerprint("3f1c9e0a5b7d2468ace0f13579bdf02468ace13579bdf02468ace13579bdf024"),
        ),
        bubble = BubbleSettings(enabled = false, side = BubbleSide.LEFT, haptics = false, soundCues = true),
        dictation = DictationSettings(sessionLimit = 3.minutes, pauseOtherAudio = false),
        cleanup = CleanupSettings(enabled = false, modelDeadline = Duration.INFINITE, skipShortPhrases = false),
        styles = Settings.Defaults.styles.copy(email = Tone.VERY_CASUAL, code = Tone.FORMAL, browser = Tone.CASUAL),
        context = ContextSettings(readAroundCursor = false, charsBefore = 120, charsAfter = 0),
        dictionary = DictionarySettings(LearnMode.AUTOMATIC),
        insertion = InsertionSettings(neverUseClipboard = true),
        privacy = PrivacySettings(historyRetention = 7.days, saveHistory = false),
        advanced = AdvancedSettings(verboseLoggingUntil = Fixtures.Epoch, useFakeEngines = true),
    )

    private fun roundTrip(settings: Settings): Pair<Settings, List<String>> {
        val preferences = mutablePreferencesOf()
        SettingsCodec.encode(settings, preferences)
        return SettingsCodec.decode(preferences.asMap().mapKeys { it.key.name }, settings.server.key)
    }

    @Test
    fun `nothing stored reads as the Appendix G defaults`() {
        assertEquals(Settings.Defaults to emptyList<String>(), SettingsCodec.decode(emptyMap(), key = null))
    }

    @Test
    fun `every setting round-trips`() {
        assertEquals(everythingChanged to emptyList<String>(), roundTrip(everythingChanged))
        assertEquals(Settings.Defaults to emptyList<String>(), roundTrip(Settings.Defaults))
    }

    @Test
    fun `a finite model deadline and a retention round-trip in milliseconds`() {
        val settings = Settings.Defaults.copy(
            cleanup = Settings.Defaults.cleanup.copy(modelDeadline = 2.5.seconds),
            privacy = PrivacySettings(7.days),
        )
        assertEquals(settings, roundTrip(settings).first)
    }

    @Test
    fun `unknown keys are ignored and invalid values fall back to defaults, reported by name only`() {
        val stored = mutablePreferencesOf(
            stringPreferencesKey("from.a.newer.version") to "whatever",
            booleanPreferencesKey("bubble.enabled") to false,
            stringPreferencesKey("bubble.haptics") to "yes",
            longPreferencesKey("dictation.session_limit_ms") to 30_000L,
            longPreferencesKey("cleanup.model_deadline_ms") to 0L,
            stringPreferencesKey("styles.email") to "shouty",
            intPreferencesKey("context.chars_before") to -5,
            stringPreferencesKey("dictionary.learn_from_corrections") to "SOMETIMES",
            stringPreferencesKey("server.certificate_sha256") to "not-hex",
            stringPreferencesKey("server.endpoints") to
                """[{"host":"192.0.2.10","port":8170},{"host":"","port":1},{"port":2}]""",
        )

        val (settings, problems) = SettingsCodec.decode(stored.asMap().mapKeys { it.key.name }, key = null)

        assertEquals(false, settings.bubble.enabled)
        assertEquals(Settings.Defaults.bubble.haptics, settings.bubble.haptics)
        assertEquals(Settings.Defaults.dictation.sessionLimit, settings.dictation.sessionLimit)
        assertEquals(Settings.Defaults.cleanup.modelDeadline, settings.cleanup.modelDeadline)
        assertEquals(Tone.FORMAL, settings.styles.email)
        assertEquals(500, settings.context.charsBefore)
        assertEquals(LearnMode.ASK, settings.dictionary.learnFromCorrections)
        assertEquals(null, settings.server.certificate)
        assertEquals(listOf(ServerEndpoint("192.0.2.10", 8170)), listOfNotNull(settings.server.address))
        assertEquals(
            listOf(
                "server.endpoints",
                "server.certificate_sha256",
                "bubble.haptics",
                "dictation.session_limit_ms",
                "cleanup.model_deadline_ms",
                "styles.email",
                "context.chars_before",
                "dictionary.learn_from_corrections",
            ).sorted(),
            problems.sorted(),
        )
    }

    @Test
    fun `a broken endpoint list reads as no endpoints`() {
        val stored = mapOf("server.endpoints" to "{not json")

        val (settings, problems) = SettingsCodec.decode(stored, key = null)

        assertEquals(emptyList<ServerEndpoint>(), listOfNotNull(settings.server.address))
        assertEquals(listOf("server.endpoints"), problems)
    }

    @Test
    fun `an install with the old address list keeps its first address and writes only that one`() {
        val stored = mapOf(
            "server.endpoints" to """[{"host":"192.0.2.10","port":8170},{"host":"100.64.0.20","port":8170}]""",
        )
        val (settings, problems) = SettingsCodec.decode(stored, key = null)
        assertEquals(ServerEndpoint("192.0.2.10", 8170), settings.server.address)
        assertEquals(emptyList<String>(), problems)

        val preferences = mutablePreferencesOf()
        SettingsCodec.encode(settings, preferences)
        val written = preferences.asMap().mapKeys { it.key.name }
        assertFalse("the list is gone once written", "server.endpoints" in written)
        assertEquals(
            ServerEndpoint("192.0.2.10", 8170),
            SettingsCodec.decode(written, key = null).first.server.address,
        )
    }

    @Test
    fun `a broken address reads as none and is reported by name`() {
        val (settings, problems) = SettingsCodec.decode(mapOf("server.address" to "{\"host\":\"\"}"), key = null)
        assertEquals(null, settings.server.address)
        assertEquals(listOf("server.address"), problems)
    }

    @Test
    fun `an install that stored history off as a retention of zero reads as switched off`() {
        val (settings, _) = SettingsCodec.decode(mapOf("privacy.history_retention_ms" to 0L), key = null)
        assertFalse(settings.privacy.saveHistory)
        assertFalse(settings.privacy.historyOn)
        val (kept, _) = SettingsCodec.decode(mapOf("privacy.history_retention_ms" to 7.days.inWholeMilliseconds), null)
        assertTrue(kept.privacy.saveHistory)
    }
}
