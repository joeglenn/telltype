package net.jbglenn.telltype.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.jbglenn.telltype.domain.port.AppRuleRepository
import net.jbglenn.telltype.domain.port.BubbleSnooze
import net.jbglenn.telltype.domain.port.DictionaryRepository
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.PendingJobRepository
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.domain.port.SnippetRepository

/**
 * Debug builds: what the connected tests (which run in the app's process) read and arrange directly, through
 * `EntryPointAccessors.fromApplication`: settings (addresses, clipboard rule), app rules, History, the queue and the
 * bubble's snooze, the dictionary and the snippets.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugTestAccess {
    /** The settings. */
    fun settings(): SettingsRepository

    /** Per-app rules (excluded apps). */
    fun appRules(): AppRuleRepository

    /** The History entries. */
    fun history(): HistoryRepository

    /** The queue of dictations waiting for the server. */
    fun pendingJobs(): PendingJobRepository

    /** The bubble's "hide for 1 hour" snooze. */
    fun bubbleSnooze(): BubbleSnooze

    /** The dictionary terms and replacements. */
    fun dictionary(): DictionaryRepository

    /** The snippets. */
    fun snippets(): SnippetRepository
}
