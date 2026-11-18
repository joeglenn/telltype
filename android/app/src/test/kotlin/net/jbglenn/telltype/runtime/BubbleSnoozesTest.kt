package net.jbglenn.telltype.runtime

import android.app.Application
import android.content.res.Resources
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.system.BubblePlacement
import net.jbglenn.telltype.testing.FakeAppRuleRepository
import net.jbglenn.telltype.testing.FakeBubbleSnooze
import net.jbglenn.telltype.testing.FakeFocusedFieldSource
import net.jbglenn.telltype.testing.FakeSettingsRepository
import net.jbglenn.telltype.testing.FixedClock
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import net.jbglenn.telltype.ui.overlay.SnoozeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The three snooze targets of the bubble (docs/PLAN.md section 5.2) and the drop position that picks one. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BubbleSnoozesTest {
    private val snooze = FakeBubbleSnooze()
    private val focused = FakeFocusedFieldSource()
    private val rules = FakeAppRuleRepository()
    private val clock = FixedClock()
    private val settings = FakeSettingsRepository()
    private val snoozes = BubbleSnoozes(snooze, focused, rules, settings, clock, RecordingLogger())
    private val chat = Fixtures.fieldSnapshot(Fixtures.fieldTarget(app = CHAT))
    private val mail = Fixtures.fieldSnapshot(Fixtures.fieldTarget(handle = "field-2", app = MAIL))

    private fun TestScope.following() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snoozes.followFocus() }
    }

    private fun placement(x: Int, inZone: Boolean = true) =
        BubblePlacement(x = x, y = 0, side = BubbleSide.RIGHT, dragging = true, inSnoozeZone = inZone)

    @Test
    fun `the column of the bubble's centre picks the target inside the band, nothing outside it`() {
        val width = Resources.getSystem().displayMetrics.widthPixels
        snoozes.bubbleWidth = 20
        assertEquals(SnoozeTarget.ONE_HOUR, snoozes.targetAt(placement(0)))
        assertEquals(SnoozeTarget.THIS_APP, snoozes.targetAt(placement(width / 2 - 10)))
        assertEquals(SnoozeTarget.NEVER_IN_THIS_APP, snoozes.targetAt(placement(width - 20)))
        assertNull(snoozes.targetAt(placement(width / 2 - 10, inZone = false)))
    }

    @Test
    fun `the finger's column picks the target when a drag reported it, wherever the window is`() {
        val width = Resources.getSystem().displayMetrics.widthPixels
        snoozes.bubbleWidth = 20
        snoozes.fingerX = width - 1f
        assertEquals(SnoozeTarget.NEVER_IN_THIS_APP, snoozes.targetAt(placement(0)))
        snoozes.fingerX = width / 2f
        assertEquals(SnoozeTarget.THIS_APP, snoozes.targetAt(placement(width - 20)))
        snoozes.fingerX = 1f
        assertEquals(SnoozeTarget.ONE_HOUR, snoozes.targetAt(placement(width / 2)))
    }

    @Test
    fun `an hour snoozes the bubble everywhere until one hour from now`() = runTest {
        following()
        snoozes.apply(SnoozeTarget.ONE_HOUR)
        runCurrent()
        assertEquals(clock.now() + 1.hours, snooze.snoozedUntil.value)
    }

    @Test
    fun `this app hides the bubble in the app in front until another app has the focus`() = runTest {
        following()
        focused.focus(chat)
        snoozes.apply(SnoozeTarget.THIS_APP)
        runCurrent()
        assertTrue("snoozed in the chat app", snoozes.appSnoozed.first())
        focused.focus(mail)
        runCurrent()
        assertFalse("not in the mail app", snoozes.appSnoozed.first())
        focused.focus(chat)
        runCurrent()
        assertFalse("the snooze ended when another app had the focus", snoozes.appSnoozed.first())
        assertNull("nothing snoozed everywhere", snooze.snoozedUntil.value)
    }

    @Test
    fun `switching the bubble off and on, and the end of a snooze, reset its place and forget the app snooze`() =
        runTest {
            following()
            val resets = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                snoozes.resets().collect { resets += it }
            }
            focused.focus(chat)
            snoozes.apply(SnoozeTarget.THIS_APP)
            runCurrent()
            assertTrue("snoozed in the chat app", snoozes.appSnoozed.first())
            assertEquals("nothing at the start", emptyList<String>(), resets)

            settings.update { it.copy(bubble = it.bubble.copy(enabled = false)) }
            runCurrent()
            assertEquals("switching off is no reset", emptyList<String>(), resets)
            settings.update { it.copy(bubble = it.bubble.copy(enabled = true)) }
            runCurrent()
            assertEquals(listOf("bubble switched on"), resets)
            assertFalse("the app snooze is forgotten", snoozes.appSnoozed.first())

            snooze.snoozeUntil(clock.now() + 1.hours)
            runCurrent()
            snooze.resume()
            runCurrent()
            assertEquals(listOf("bubble switched on", "snooze ended"), resets)
        }

    @Test
    fun `a new connection forgets the app snooze`() = runTest {
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snoozes.followFocus() }
        focused.focus(chat)
        snoozes.apply(SnoozeTarget.THIS_APP)
        runCurrent()
        assertTrue("snoozed in the chat app", snoozes.appSnoozed.first())
        first.cancel()
        following()
        runCurrent()
        assertFalse("the next connection shows the bubble in the chat app again", snoozes.appSnoozed.first())
    }

    @Test
    fun `never in this app saves an excluded rule for the app in front`() = runTest {
        following()
        focused.focus(chat)
        snoozes.apply(SnoozeTarget.NEVER_IN_THIS_APP)
        runCurrent()
        val rule = rules.find(AppPackage(CHAT))
        assertEquals(
            Outcome.Ok(AppRule(AppPackage(CHAT), null, null, excluded = true)),
            rule,
        )
    }

    @Test
    fun `never in this app without a focused app saves nothing`() = runTest {
        following()
        snoozes.apply(SnoozeTarget.NEVER_IN_THIS_APP)
        runCurrent()
        assertEquals(emptyList<AppRule>(), rules.rules.first())
    }

    private companion object {
        const val CHAT = "org.example.chat"
        const val MAIL = "org.example.mail"
    }
}
