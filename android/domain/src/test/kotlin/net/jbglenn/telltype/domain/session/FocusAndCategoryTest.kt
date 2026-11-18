package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.field.FieldKind
import net.jbglenn.telltype.core.model.field.FieldSnapshot
import net.jbglenn.telltype.core.model.field.TextRange
import net.jbglenn.telltype.core.model.session.BubbleMode
import net.jbglenn.telltype.core.model.session.Focus
import net.jbglenn.telltype.core.model.session.UnavailableReason
import net.jbglenn.telltype.core.model.settings.BubbleSettings
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.testing.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Bubble eligibility (plan 4.1, 7.5) and app categories (Appendix J). */
class FocusAndCategoryTest {
    private val target = Fixtures.fieldTarget()
    private val snapshot = Fixtures.fieldSnapshot(target)
    private val bubble = BubbleSettings(enabled = true, side = BubbleSide.RIGHT, haptics = true, soundCues = false)
    private val base = FocusInput(snapshot, null, bubble, false, Fixtures.permissionsGranted(), true)
    private val dictate = Focus.Available(target, BubbleMode.DICTATE)

    private fun withKind(kind: FieldKind): FocusInput {
        val kindTarget = target.copy(kind = kind)
        return base.copy(snapshot = Fixtures.fieldSnapshot(kindTarget))
    }

    private fun rule(app: String = target.app.name, excluded: Boolean = false, category: AppCategory? = null) =
        AppRule(AppPackage(app), category, null, excluded)

    private val focusCases = listOf(
        Triple("plain field", base, dictate),
        Triple("no field", base.copy(snapshot = null), Focus.None),
        Triple(
            "keyboard hidden",
            base.copy(snapshot = Fixtures.fieldSnapshot(target, keyboardVisible = false)),
            Focus.None,
        ),
        Triple("password field", withKind(FieldKind.PASSWORD), Focus.None),
        Triple("numeric or phone field", withKind(FieldKind.NUMERIC), Focus.None),
        Triple(
            "single-line field",
            withKind(FieldKind.SINGLE_LINE),
            Focus.Available(target.copy(kind = FieldKind.SINGLE_LINE), BubbleMode.DICTATE),
        ),
        Triple(
            "incognito field is allowed",
            base.copy(snapshot = Fixtures.fieldSnapshot(target.copy(incognito = true))),
            Focus.Available(target.copy(incognito = true), BubbleMode.DICTATE),
        ),
        Triple("excluded app", base.copy(appRule = rule(excluded = true)), Focus.None),
        Triple("rule of another app ignored", base.copy(appRule = rule(app = "org.other", excluded = true)), dictate),
        Triple("rule without exclusion", base.copy(appRule = rule(category = AppCategory.EMAIL)), dictate),
        Triple("bubble off", base.copy(bubble = bubble.copy(enabled = false)), Focus.None),
        Triple("snoozed", base.copy(snoozed = true), Focus.None),
        Triple(
            "accessibility off",
            base.copy(permissions = Fixtures.permissionsGranted().copy(accessibilityEnabled = false)),
            Focus.None,
        ),
        Triple(
            "microphone missing",
            base.copy(permissions = Fixtures.permissionsGranted().copy(microphoneGranted = false)),
            Focus.Blocked(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
        ),
        Triple(
            "no server",
            base.copy(serverConfigured = false),
            Focus.Blocked(UnavailableReason.SERVER_NOT_CONFIGURED),
        ),
        Triple(
            "microphone checked before server",
            base.copy(
                serverConfigured = false,
                permissions = Fixtures.permissionsGranted().copy(microphoneGranted = false),
            ),
            Focus.Blocked(UnavailableReason.MICROPHONE_PERMISSION_MISSING),
        ),
        Triple(
            "password field without microphone shows nothing",
            withKind(FieldKind.PASSWORD).copy(
                permissions = Fixtures.permissionsGranted().copy(microphoneGranted = false),
            ),
            Focus.None,
        ),
        Triple(
            "selection gives command mode",
            base.copy(snapshot = selected(TextRange(2, 7))),
            Focus.Available(target, BubbleMode.COMMAND),
        ),
        Triple("empty selection is a cursor", base.copy(snapshot = selected(TextRange(3, 3))), dictate),
        Triple("no selection known", base.copy(snapshot = selected(null)), dictate),
    )

    @TestFactory
    fun `focus rules`(): List<DynamicTest> = focusCases.map { (name, input, expected) ->
        dynamicTest(name) { assertEquals(expected, evaluateFocus(input)) }
    }

    private fun selected(range: TextRange?): FieldSnapshot = Fixtures.fieldSnapshot(target, selection = range)

    @TestFactory
    fun `categories`(): List<DynamicTest> = listOf(
        Triple("com.google.android.gm", null, AppCategory.EMAIL),
        Triple("com.Slack", null, AppCategory.WORK_CHAT),
        Triple("com.whatsapp", null, AppCategory.PERSONAL_CHAT),
        Triple("md.obsidian", null, AppCategory.NOTES_DOCUMENTS),
        Triple("com.openai.chatgpt", null, AppCategory.AI_CHAT),
        Triple("com.termux", null, AppCategory.CODE),
        Triple("com.android.chrome", null, AppCategory.BROWSER),
        Triple("org.unknown", null, AppCategory.OTHER),
        Triple("com.whatsapp", rule("com.whatsapp", category = AppCategory.WORK_CHAT), AppCategory.WORK_CHAT),
        Triple("org.unknown", rule("org.unknown", category = AppCategory.EMAIL), AppCategory.EMAIL),
        Triple("com.whatsapp", rule("com.whatsapp"), AppCategory.PERSONAL_CHAT),
        Triple("com.whatsapp", rule("com.Slack", category = AppCategory.EMAIL), AppCategory.PERSONAL_CHAT),
    ).map { (app, rule, expected) ->
        dynamicTest("$app ${rule?.categoryOverride}") { assertEquals(expected, resolveCategory(AppPackage(app), rule)) }
    }
}
