package net.jbglenn.telltype.testing

import app.cash.turbine.test
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.error.ServerUnreachable
import net.jbglenn.telltype.core.model.session.FeedbackCue
import net.jbglenn.telltype.core.model.settings.ServerReachability
import net.jbglenn.telltype.core.model.system.Notice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SystemFakesTest {
    @Test
    fun `clipboard holds the latest copy`() = runTest {
        val clipboard = FakeClipboard()

        assertNull(clipboard.content)
        clipboard.copy("one")
        clipboard.copy("two")
        assertEquals("two", clipboard.content)
        assertEquals(listOf("one", "two"), clipboard.copyCall.calls)
    }

    @Test
    fun `haptics remember every cue`() {
        val haptics = FakeHaptics()

        haptics.play(FeedbackCue.RECORDING_STARTED)
        haptics.play(FeedbackCue.PROBLEM)
        assertEquals(listOf(FeedbackCue.RECORDING_STARTED, FeedbackCue.PROBLEM), haptics.played)
    }

    @Test
    fun `notifier remembers every notice`() = runTest {
        val notifier = FakeNotifier()
        val notice = Notice.TranscribedLater(SessionId("session-1"), "Hello world.")

        notifier.post(notice)
        notifier.post(Notice.AccessibilityOff)
        assertEquals(listOf(notice, Notice.AccessibilityOff), notifier.postCall.calls)
        notifier.cancel(Notice.AccessibilityOff)
        assertEquals(listOf<Notice>(Notice.AccessibilityOff), notifier.cancelCall.calls)
    }

    @Test
    fun `fix launcher remembers every fix`() = runTest {
        val launcher = FakeFixLauncher()

        launcher.open(FixTarget.SERVER_SETUP)
        assertEquals(listOf(FixTarget.SERVER_SETUP), launcher.openCall.calls)
    }

    @Test
    fun `health source publishes what a probe returns`() = runTest {
        val health = FakeServerHealthSource()
        val down = ServerReachability.Unreachable(ServerUnreachable, Fixtures.Epoch)

        health.reachability.test {
            assertEquals(ServerReachability.Unknown, awaitItem())
            health.probeCall.returns(down)
            health.probeCall.delay = 100.milliseconds
            assertEquals(down, health.probe())
            assertEquals(down, awaitItem())
        }
    }

    @Test
    fun `permission source shows a device change only after refresh`() = runTest {
        val permissions = FakePermissionStatusSource()
        val revoked = Fixtures.permissionsGranted().copy(microphoneGranted = false)

        permissions.changeDevice(revoked)
        assertEquals(Fixtures.permissionsGranted(), permissions.status.value)
        assertEquals(revoked, permissions.refresh())
        assertEquals(revoked, permissions.status.value)
        assertEquals(1, permissions.refreshCount)
    }
}
