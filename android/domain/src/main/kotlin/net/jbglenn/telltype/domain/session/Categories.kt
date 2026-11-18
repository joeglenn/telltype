package net.jbglenn.telltype.domain.session

import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule

/**
 * The shipped app category map (docs/PLAN.md Appendix J). Package names are to be checked against the Pixel in
 * work package P1; the user overrides any of them with an `AppRule`.
 */
internal val SeedCategories: Map<String, AppCategory> = buildMap {
    listOf("com.google.android.gm", "com.microsoft.office.outlook").forEach { put(it, AppCategory.EMAIL) }
    listOf("com.Slack", "com.microsoft.teams", "com.discord").forEach { put(it, AppCategory.WORK_CHAT) }
    listOf(
        "com.google.android.apps.messaging",
        "com.whatsapp",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.facebook.orca",
        "com.instagram.android",
    ).forEach { put(it, AppCategory.PERSONAL_CHAT) }
    listOf("com.google.android.keep", "com.google.android.apps.docs.editors.docs", "md.obsidian")
        .forEach { put(it, AppCategory.NOTES_DOCUMENTS) }
    listOf("com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard")
        .forEach { put(it, AppCategory.AI_CHAT) }
    put("com.termux", AppCategory.CODE)
    listOf("com.android.chrome", "org.mozilla.firefox", "com.brave.browser").forEach { put(it, AppCategory.BROWSER) }
}

/** The rule's override when [rule] is for [app] and has one, else the seed map, else [AppCategory.OTHER]. */
internal fun categoryOf(app: AppPackage, rule: AppRule?): AppCategory =
    rule?.takeIf { it.app == app }?.categoryOverride ?: SeedCategories[app.name] ?: AppCategory.OTHER
