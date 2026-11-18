package net.jbglenn.telltype.runtime

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.error.FieldGone
import net.jbglenn.telltype.core.model.error.NoSpeech
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.system.Notice
import net.jbglenn.telltype.testing.FakeNotifier
import net.jbglenn.telltype.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RuntimeNoticesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val notifier = FakeNotifier()
    private val notices = RuntimeNotices(context, notifier)

    private fun switch(on: Boolean) {
        val value = if (on) "${context.packageName}/net.jbglenn.telltype.platform.service.Service" else ""
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    @Test
    fun `switching the service off posts the notice once, after the grace period`() = runTest {
        val connected = MutableStateFlow(false)
        val job = launch { notices.accessibilityOff(connected) }
        runCurrent()
        assertEquals("never connected: nothing to report", emptyList<Notice>(), notifier.postCall.calls)
        connected.value = true
        runCurrent()
        switch(on = false)
        connected.value = false
        advanceTimeBy(RuntimeNotices.OFF_GRACE.inWholeMilliseconds - 1)
        assertEquals(emptyList<Notice>(), notifier.postCall.calls)
        advanceTimeBy(2)
        assertEquals(listOf<Notice>(Notice.AccessibilityOff), notifier.postCall.calls)
        job.cancel()
    }

    @Test
    fun `the notice is removed when the service connects again`() = runTest {
        val connected = MutableStateFlow(true)
        val job = launch { notices.accessibilityOff(connected) }
        runCurrent()
        switch(on = false)
        connected.value = false
        advanceTimeBy(RuntimeNotices.OFF_GRACE.inWholeMilliseconds + 1)
        assertEquals(listOf<Notice>(Notice.AccessibilityOff), notifier.postCall.calls)
        val cancelledBefore = notifier.cancelCall.calls.size

        switch(on = true)
        connected.value = true
        runCurrent()

        assertEquals(cancelledBefore + 1, notifier.cancelCall.calls.size)
        assertEquals(Notice.AccessibilityOff, notifier.cancelCall.calls.last())
        job.cancel()
    }

    @Test
    fun `a disconnection that comes back or keeps the setting on posts nothing`() = runTest {
        switch(on = true)
        val connected = MutableStateFlow(true)
        val job = launch { notices.accessibilityOff(connected) }
        runCurrent()
        switch(on = false)
        connected.value = false
        advanceTimeBy(RuntimeNotices.OFF_GRACE.inWholeMilliseconds / 2)
        switch(on = true)
        connected.value = true
        runCurrent()
        connected.value = false
        advanceTimeBy(RuntimeNotices.OFF_GRACE.inWholeMilliseconds * 2)
        assertEquals(
            "setting still on (services suppressed for a moment)",
            emptyList<Notice>(),
            notifier.postCall.calls,
        )
        job.cancel()
    }

    @Test
    fun `a problem without a bubble posts one notice, a problem on a visible bubble none`() = runTest {
        val session = Fixtures.session()
        val hidden = SessionState.Problem(NoSpeech, session, null, Focus.None)
        val visibleOther = SessionState.Problem(FieldGone, Fixtures.session(), null, Focus.None)
        notices.problemsWhileHidden(
            flowOf(
                SessionState.Hidden to false,
                hidden to false,
                hidden.copy(focus = Focus.None) to false,
                visibleOther to true,
                visibleOther to false,
            ),
        )
        assertEquals(listOf<Notice>(Notice.ProblemWhileHidden(NoSpeech)), notifier.postCall.calls)
    }
}
