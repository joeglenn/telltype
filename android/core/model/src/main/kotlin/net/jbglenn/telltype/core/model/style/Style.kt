package net.jbglenn.telltype.core.model.style

import net.jbglenn.telltype.core.model.AppPackage

/**
 * The kind of app in front, which picks the tone (docs/PLAN.md section 4.3 and Appendix J).
 *
 * @property id stable id, identical to the keys of `shared/prompts/categories.json` and the value stored in the
 *   `history.category` and `app_rule.category_override` columns. Never change one.
 */
public enum class AppCategory(public val id: String) {
    /** Gmail, Outlook. */
    EMAIL("email"),

    /** Slack, Teams, Discord. */
    WORK_CHAT("work_chat"),

    /** Messages, WhatsApp, Telegram, Signal. */
    PERSONAL_CHAT("personal_chat"),

    /** Keep, Docs, Obsidian. */
    NOTES_DOCUMENTS("notes_documents"),

    /** ChatGPT, Claude, Gemini. */
    AI_CHAT("ai_chat"),

    /** Termux and other code editors. */
    CODE("code"),

    /** Chrome, Firefox, Brave. */
    BROWSER("browser"),

    /** Everything not in a seed list and not overridden. */
    OTHER("other"),
}

/**
 * How cleanup should sound (docs/PLAN.md Appendix D, tone lines).
 *
 * @property id stable id, identical to the keys of `shared/prompts/tones.json` and the value stored in the
 *   `pending_job.tone` and `app_rule.tone_override` columns. Never change one.
 */
public enum class Tone(public val id: String) {
    /** Keep the speaker's own wording and register. */
    AS_SPOKEN("as_spoken"),

    /** Complete sentences, standard punctuation, no slang. */
    FORMAL("formal"),

    /** Relaxed and natural. */
    CASUAL("casual"),

    /** Like a quick text message. */
    VERY_CASUAL("very_casual"),
}

/**
 * The user's per-app rule, one row of the `app_rule` table (docs/PLAN.md Appendix E). Apps without a row use the
 * shipped category map and the category's tone. The exclusion list (Appendix J seeds plus "Never show in this
 * app") is the set of rules with [excluded] set.
 *
 * @property app the package the rule applies to; the key.
 * @property categoryOverride the category to use instead of the shipped map, null for no override.
 * @property toneOverride the tone to use instead of the category's tone, null for no override.
 * @property excluded the bubble is never shown in this app.
 */
public data class AppRule(
    public val app: AppPackage,
    public val categoryOverride: AppCategory?,
    public val toneOverride: Tone?,
    public val excluded: Boolean,
)
