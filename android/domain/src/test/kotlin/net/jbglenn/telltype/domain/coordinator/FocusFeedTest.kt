package net.jbglenn.telltype.domain.coordinator

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.error.FixTarget
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.session.BubbleAction
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Chip
import net.jbglenn.telltype.core.model.session.SessionEvent
import net.jbglenn.telltype.core.model.session.SessionState
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The focus feed: field, settings, permissions, app rules and snooze through `evaluateFocus`, de-duplicated. */
class FocusFeedTest {
    @Test
    fun `the bubble follows the focused field and probes the server once when it appears`() = runTest {
        val w = CoordinatorWorld(this)
        w.start(focus = false)
        assertEquals(SessionState.Hidden, w.state)
        w.focus()
        w.focus()
        assertEquals(SessionState.Ready(w.field, BubbleMode.DICTATE), w.state)
        assertEquals(1, w.health.probeCall.calls.size, "an unchanged focus is not sent again")
        w.focus(keyboard = false)
        assertEquals(SessionState.Hidden, w.state)
        w.focus(Fixtures.fieldTarget(kind = FieldKind.PASSWORD))
        assertEquals(SessionState.Hidden, w.state)
        w.focus(selection = TextRange(2, 6))
        assertEquals(SessionState.Ready(w.field, BubbleMode.COMMAND), w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a missing microphone permission shows the bubble as unavailable with its explanation and fix`() = runTest {
        val w = CoordinatorWorld(this)
        w.permissions.changeDevice(Fixtures.permissionsGranted().copy(microphoneGranted = false))
        w.permissions.refresh()
        w.start()
        assertEquals(SessionState.Unavailable(UnavailableReason.MICROPHONE_PERMISSION_MISSING), w.state)
        w.send(SessionEvent.MicPressed, SessionEvent.ActionTapped(BubbleAction.FIX))
        assertEquals(listOf<Chip>(Chip.Explanation(UnavailableReason.MICROPHONE_PERMISSION_MISSING)), w.chips)
        assertEquals(listOf(FixTarget.MICROPHONE_PERMISSION), w.fixLauncher.openCall.calls)
        w.closeAndAssertStopped()
    }

    @Test
    fun `no server is unavailable until one is configured or the fake engines are on`() = runTest {
        val w = CoordinatorWorld(this, settings = configuredSettings().copy(server = ServerConfig.Unconfigured))
        w.start()
        assertEquals(SessionState.Unavailable(UnavailableReason.SERVER_NOT_CONFIGURED), w.state)
        w.settingsRepository.update { it.copy(advanced = it.advanced.copy(useFakeEngines = true)) }
        w.runCurrent()
        assertEquals(SessionState.Ready(w.field, BubbleMode.DICTATE), w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `a snooze hides the bubble until it ends by itself`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.snooze.snoozeUntil(w.clock.now() + 10.minutes)
        w.runCurrent()
        assertEquals(SessionState.Hidden, w.state)
        w.advance(10.minutes)
        assertEquals(SessionState.Ready(w.field, BubbleMode.DICTATE), w.state)
        w.closeAndAssertStopped()
    }

    @Test
    fun `app rules exclude apps and override category and tone`() = runTest {
        val w = CoordinatorWorld(this)
        w.start()
        w.appRules.upsert(AppRule(AppPackage("org.example.chat"), null, null, excluded = true))
        w.runCurrent()
        assertEquals(SessionState.Hidden, w.state)
        w.appRules.upsert(AppRule(w.field.app, AppCategory.EMAIL, Tone.VERY_CASUAL, excluded = false))
        w.runCurrent()
        assertEquals(SessionState.Ready(w.field, BubbleMode.DICTATE), w.state)
        w.dictate()
        val request = w.cleaner.cleanCall.calls.single()
        assertEquals(AppCategory.EMAIL, request.category)
        assertEquals(Tone.VERY_CASUAL, request.tone)
        assertEquals(AppCategory.EMAIL, w.history.entries.single().category)
        w.closeAndAssertStopped()
    }
}
