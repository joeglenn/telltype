package net.jbglenn.telltype.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.e2e.Device.HOST
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Package V1, feature F1 (docs/PLAN.md section 4.3), done when "the same sentence dictated into Gmail and into
 * Telegram comes out in the two configured tones". The emulator has neither app, so the closest automated equivalent:
 * the test host is mapped to Gmail's category (email) and then to Telegram's (personal chat) by a per-app rule, with
 * the configured tones Formal and Very casual, and the same clip is dictated against the live server in each. The
 * shipped map itself (Gmail -> email, Telegram -> personal chat) is `FocusAndCategoryTest` in `:domain`.
 */
@RunWith(AndroidJUnit4::class)
class ToneTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        FeatureDictation.realEngines()
        Arrange.settings {
            it.copy(
                styles = it.styles.copy(email = Tone.FORMAL, personalChat = Tone.VERY_CASUAL),
                cleanup = it.cleanup.copy(enabled = true),
            )
        }
    }

    @After
    fun tearDown() {
        Arrange.removeRule(HOST)
        FeatureDictation.defaultClip()
    }

    @Test
    fun theSameSentenceComesOutInTheEmailAndTheChatTone() {
        Arrange.style(HOST, AppCategory.EMAIL)
        val email = FeatureDictation.dictate("tone")
        assertEquals(AppCategory.EMAIL, FeatureDictation.lastEntry().category)
        Device.screenshot("v1-f1-email-formal")

        Arrange.style(HOST, AppCategory.PERSONAL_CHAT)
        val chat = FeatureDictation.dictate("tone")
        val chatEntry = FeatureDictation.lastEntry()
        assertEquals(AppCategory.PERSONAL_CHAT, chatEntry.category)
        assertEquals(DictationMode.DICTATE, chatEntry.mode)
        Device.screenshot("v1-f1-chat-very-casual")

        assertNotEquals("the two tones differ", email, chat)
        assertFalse("formal writes 'going to', not 'gonna': '$email'", email.contains("gonna", ignoreCase = true))
        assertTrue("formal ends with a full stop: '$email'", email.trimEnd().endsWith("."))
        assertFalse("very casual has no final full stop: '$chat'", chat.trimEnd().endsWith("."))
    }
}
