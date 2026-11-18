package net.jbglenn.telltype.audio.platform

import android.Manifest
import android.app.Application
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.audio.Interruption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AndroidPlatformAudioTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val audioManager = application.getSystemService(AudioManager::class.java)

    @Test
    fun focusIsRequestedExclusivelyForSpeechAndAbandonedOnRelease() {
        val focus = AndroidAudioFocus(application)

        val held = requireNotNull(focus.request {})

        val request = shadowOf(audioManager).lastAudioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, request.durationHint)
        held.release()
        held.release()
        assertNotNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun aRefusedFocusRequestReturnsNothing() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertNull(AndroidAudioFocus(application).request {})
    }

    @Test
    fun lossAndTransientLossCountButADuckDoesNot() {
        var lost = 0
        AndroidAudioFocus(application).request { lost++ }
        val listener = shadowOf(audioManager).lastAudioFocusRequest.listener

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0, lost)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(2, lost)
    }

    @Test
    fun screenOffIsReportedUntilReleased() {
        val heard = mutableListOf<Interruption>()
        val watch = AndroidInterruptionMonitor(application).watch { heard += it }

        application.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(Interruption.SCREEN_OFF), heard)

        watch.release()
        watch.release()
        application.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, heard.size)
    }

    @Test
    fun ringingAndCallModesAreCallsOthersAreNot() {
        val calls = listOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
        )
        calls.forEach { assertTrue("mode $it", AndroidInterruptionMonitor.isCallMode(it)) }
        assertFalse(AndroidInterruptionMonitor.isCallMode(AudioManager.MODE_NORMAL))
    }

    @Test
    fun permissionFollowsTheGrant() {
        val permission = AndroidMicrophonePermission(application)

        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(permission.isGranted())

        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(permission.isGranted())
    }
}
