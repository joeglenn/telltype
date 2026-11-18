package net.jbglenn.telltype.platform.system

import android.Manifest
import android.media.ToneGenerator
import android.os.VibrationEffect
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.KeyRejected
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.core.model.system.PermissionStatus
import net.jbglenn.telltype.platform.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeContentTest {
    @Test
    fun `a later transcription carries its text privately, with one notification per session`() {
        val first = NoticeContent.of(Notice.TranscribedLater(SessionId("a"), "Hello world."))
        val second = NoticeContent.of(Notice.TranscribedLater(SessionId("b"), "Bye."))

        assertEquals(NotificationChannels.RESULTS, first.channel)
        assertEquals("Hello world.", first.privateText)
        assertEquals(R.string.notice_transcribed_public, first.text)
        assertNotEquals(first.id, second.id)
        assertFalse(first.toString().contains("Hello"))
    }

    @Test
    fun `recovered dictations use the plural with their count`() {
        val spec = NoticeContent.of(Notice.DictationsRecovered(3))

        assertEquals(R.plurals.notice_recovered_text, spec.text)
        assertEquals(3, spec.count)
        assertNull(spec.privateText)
    }

    @Test
    fun `problems go to the problems channel and lead to their fix`() {
        assertEquals(FixTarget.ACCESSIBILITY_SERVICE, NoticeContent.of(Notice.AccessibilityOff).fix)
        assertNull(NoticeContent.of(Notice.AdvancedProtectionOn).fix)
        assertEquals(FixTarget.SERVER_SETUP, NoticeContent.of(Notice.ProblemWhileHidden(KeyRejected)).fix)
        assertNull(NoticeContent.of(Notice.ProblemWhileHidden(NoSpeech)).fix)
        listOf(Notice.AccessibilityOff, Notice.AdvancedProtectionOn, Notice.ProblemWhileHidden(NoSpeech))
            .forEach { assertEquals(NotificationChannels.PROBLEMS, NoticeContent.of(it).channel) }
    }
}

class FeedbackEffectsTest {
    @Test
    fun `confirm on start and stop, a light tick when done, a double click on a problem`() {
        assertEquals(VibrationEffect.EFFECT_CLICK, FeedbackEffects.vibration(FeedbackCue.RECORDING_STARTED))
        assertEquals(VibrationEffect.EFFECT_CLICK, FeedbackEffects.vibration(FeedbackCue.RECORDING_STOPPED))
        assertEquals(VibrationEffect.EFFECT_TICK, FeedbackEffects.vibration(FeedbackCue.DONE))
        assertEquals(VibrationEffect.EFFECT_DOUBLE_CLICK, FeedbackEffects.vibration(FeedbackCue.PROBLEM))
    }

    @Test
    fun `tones only for start and stop`() {
        assertEquals(ToneGenerator.TONE_PROP_BEEP, FeedbackEffects.tone(FeedbackCue.RECORDING_STARTED))
        assertEquals(ToneGenerator.TONE_PROP_ACK, FeedbackEffects.tone(FeedbackCue.RECORDING_STOPPED))
        assertNull(FeedbackEffects.tone(FeedbackCue.DONE))
        assertNull(FeedbackEffects.tone(FeedbackCue.PROBLEM))
    }
}

class PermissionReaderTest {
    private class Probe(
        val granted: Set<String> = emptySet(),
        val notifications: Boolean = true,
        val accessibility: Boolean = true,
        val advancedProtection: Boolean = false,
    ) : PermissionProbe {
        var advancedProtectionAsked = false

        override fun granted(permission: String): Boolean = permission in granted

        override fun notificationsEnabled(): Boolean = notifications

        override fun accessibilityServiceRunning(): Boolean = accessibility

        override fun advancedProtectionOn(): Boolean = advancedProtection.also { advancedProtectionAsked = true }
    }

    private val everything = setOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        PermissionReader.ACCESS_LOCAL_NETWORK,
    )

    @Test
    fun `on Android 15 local network is granted and Advanced Protection is off without asking`() {
        val probe = Probe(granted = setOf(Manifest.permission.RECORD_AUDIO), advancedProtection = true)

        val status = PermissionReader(sdkInt = 35, probe).read()

        assertEquals(PermissionStatus(true, true, false, true, false), status)
        assertFalse(probe.advancedProtectionAsked)
    }

    @Test
    fun `on Android 16 Advanced Protection is read`() {
        val probe = Probe(granted = everything, advancedProtection = true)

        assertTrue(PermissionReader(sdkInt = 36, probe).read().advancedProtectionOn)
        assertTrue(probe.advancedProtectionAsked)
    }

    @Test
    fun `on Android 17 the local network permission is checked`() {
        val withoutLocalNetwork = Probe(granted = everything - PermissionReader.ACCESS_LOCAL_NETWORK)

        assertFalse(PermissionReader(37, withoutLocalNetwork).read().localNetworkGranted)
        assertTrue(PermissionReader(37, Probe(granted = everything)).read().localNetworkGranted)
    }

    @Test
    fun `notifications need the permission and the app switch`() {
        val switchedOff = Probe(granted = everything, notifications = false)

        assertFalse(PermissionReader(35, switchedOff).read().notificationsGranted)
        assertTrue(PermissionReader(35, Probe(granted = everything)).read().notificationsGranted)
    }

    @Test
    fun `accessibility comes from the probe`() {
        val off = Probe(granted = everything, accessibility = false)

        assertFalse(PermissionReader(35, off).read().accessibilityEnabled)
    }
}
