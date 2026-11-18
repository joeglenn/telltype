package net.jbglenn.telltype.network.cleanup

import net.jbglenn.telltype.domain.cleanup.GuardRules
import net.jbglenn.telltype.domain.cleanup.PromptSet

/**
 * The parsed shared files the cleaner works with: the prompts it sends (`shared/prompts/`) and the rules its
 * replies are judged by (`shared/guards.json`).
 *
 * @property prompts the prompt set.
 * @property rules the guard rules.
 */
internal class CleanupAssets(val prompts: PromptSet, val rules: GuardRules)
