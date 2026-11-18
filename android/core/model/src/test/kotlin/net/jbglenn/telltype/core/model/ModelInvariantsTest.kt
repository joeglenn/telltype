package net.jbglenn.telltype.core.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import net.jbglenn.telltype.core.model.audio.AudioLevel
import net.jbglenn.telltype.core.model.field.FieldContext
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.FieldTarget
import net.jbglenn.telltype.core.model.field.ScreenRect
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerRole
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.speech.Transcript
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelInvariantsTest {
    private val target =
        FieldTarget(FieldHandle("f"), AppPackage("org.example"), FieldKind.MULTI_LINE, hint = "To", incognito = false)

    @Test
    fun `types holding dictated text or secrets never print them`() {
        val printed = listOf(
            Transcript("secret words", 1.seconds, emptyList()),
            FieldContext(textBefore = "secret before", textAfter = "secret after", selectedText = "secret sel"),
            target.copy(hint = "secret hint"),
            ServerKey("secret-key"),
        ).joinToString()

        assertFalse(printed.contains("secret"), printed)
    }

    @Test
    fun `value invariants are enforced`() {
        assertThrows<IllegalArgumentException> { TextRange(3, 2) }
        assertThrows<IllegalArgumentException> { ScreenRect(left = 10, top = 0, right = 5, bottom = 0) }
        assertThrows<IllegalArgumentException> { AudioLevel(1.5f) }
        assertThrows<IllegalArgumentException> { ServerEndpoint("server.local", 0) }
        assertThrows<IllegalArgumentException> { CertificateFingerprint("AB:CD") }
        assertTrue(TextRange(4, 4).isEmpty)
    }

    @Test
    fun `endpoints are https unless plain HTTP is chosen, and bracket IPv6 hosts`() {
        assertEquals("https://192.0.2.10:8170", ServerEndpoint("192.0.2.10", 8170).baseUrl)
        assertEquals("https://[fd00::1]:8170", ServerEndpoint("fd00::1", 8170).baseUrl)
        assertEquals("http://[fd00::1]:8000", ServerEndpoint("fd00::1", 8000, Scheme.HTTP).baseUrl)
    }

    @Test
    fun `each role has its address and certificate, text falling back to the server address`() {
        val pin = CertificateFingerprint("a".repeat(64))
        val other = CertificateFingerprint("b".repeat(64))
        val speech = ServerEndpoint("192.0.2.10", 8170)
        val one = ServerConfig(speech, key = null, certificate = pin)
        assertTrue(one.isConfigured, "key and certificate are optional (D60)")
        assertEquals(speech, one.endpointFor(ServerRole.TEXT))
        assertEquals(pin, one.certificateFor(ServerRole.TEXT))
        val text = ServerEndpoint("192.0.2.11", 8080, Scheme.HTTP)
        val two = one.copy(textAddress = text, textCertificate = other)
        assertEquals(text, two.endpointFor(ServerRole.TEXT))
        assertEquals(other, two.certificateFor(ServerRole.TEXT))
        assertEquals(pin, two.certificateFor(ServerRole.SPEECH))
    }

    @Test
    fun `a snapshot knows keyboard and selection`() {
        val field = ScreenRect(0, 0, 10, 10)
        val snapshot = FieldSnapshot(target, field, keyboardBounds = null, selection = TextRange(2, 5))

        assertFalse(snapshot.keyboardVisible)
        assertTrue(snapshot.hasSelection)
        assertFalse(snapshot.copy(selection = TextRange(2, 2)).hasSelection)
        assertFalse(FieldKind.PASSWORD.acceptsDictation || FieldKind.NUMERIC.acceptsDictation)
    }

    @Test
    fun `defaults are those of plan Appendix G`() {
        val defaults = Settings.Defaults

        assertEquals(ServerConfig.Unconfigured, defaults.server)
        assertFalse(defaults.server.isConfigured)
        assertTrue(defaults.bubble.enabled && defaults.bubble.haptics && !defaults.bubble.soundCues)
        assertEquals(5.minutes, defaults.dictation.sessionLimit)
        assertEquals(2.5.seconds, defaults.cleanup.modelDeadline)
        assertEquals(Tone.CASUAL, defaults.styles.toneFor(AppCategory.PERSONAL_CHAT))
        assertEquals(Tone.AS_SPOKEN, defaults.styles.toneFor(AppCategory.CODE))
        assertEquals(Tone.FORMAL, defaults.styles.toneFor(AppCategory.EMAIL))
        assertEquals(500, defaults.context.charsBefore)
        assertEquals(200, defaults.context.charsAfter)
        assertEquals(LearnMode.ASK, defaults.dictionary.learnFromCorrections)
        assertFalse(defaults.insertion.neverUseClipboard)
        assertEquals(30.days, defaults.privacy.historyRetention)
        assertEquals(null, defaults.advanced.verboseLoggingUntil)
        assertTrue(Duration.INFINITE > defaults.cleanup.modelDeadline)
    }
}
