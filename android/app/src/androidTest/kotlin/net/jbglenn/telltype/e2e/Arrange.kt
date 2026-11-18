package net.jbglenn.telltype.e2e

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.data.DictionaryTerm
import net.jbglenn.telltype.core.model.data.EntrySource
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.data.PendingJob
import net.jbglenn.telltype.core.model.data.Snippet
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.AppRule
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.debug.DebugTestAccess

/**
 * Arranges and reads the app's state directly (the tests run in the app's process): settings, app rules, History
 * and the queue, through the debug build's [DebugTestAccess] entry point. Never logs a key or a dictated text.
 */
internal object Arrange {
    private const val LIMIT_MS = 10_000L

    /** The server's gateway as the emulator reaches it. */
    val GATE = ServerEndpoint("10.0.2.2", 8170)

    private val access: DebugTestAccess
        get() = EntryPointAccessors.fromApplication(Device.app, DebugTestAccess::class.java)

    private fun <T> call(block: suspend DebugTestAccess.() -> T): T =
        runBlocking { withTimeout(LIMIT_MS) { access.block() } }

    /** The instrumentation argument with the gate certificate's SHA-256 (hex; not a secret). */
    const val CERT_ARGUMENT = "owkCertSha256"

    /** Where the wrapper pushes the gate key (`android/scripts/connected-tests.sh`); never logged. */
    const val KEY_FILE = "/data/local/tmp/owk-gate.key"

    /**
     * The first and last four-byte groups of the gate's certificate fingerprint as Setup shows them
     * (`AB:CD:EF:01`), from the instrumentation argument the host reads from the certificate; never written into
     * the test sources.
     */
    fun certificateGroups(): Pair<String, String> {
        val pin = checkNotNull(InstrumentationRegistry.getArguments().getString(CERT_ARGUMENT)) {
            "pass -e $CERT_ARGUMENT <sha256 hex> (android/scripts/connected-tests.sh does)"
        }.lowercase().replace(":", "")
        fun group(hex: String) = hex.chunked(2).joinToString(":").uppercase()
        return group(pin.take(GROUP_HEX)) to group(pin.takeLast(GROUP_HEX))
    }

    /**
     * A configured app, so every test class can run on its own: grants the microphone and notification
     * permissions, and, unless setup is complete, sets the server's address, the key from [KEY_FILE] and the pinned
     * certificate from the instrumentation argument [CERT_ARGUMENT]. `EndToEndTest.a_setupThroughTheRealScreens`
     * stays the one test of the real Setup screens.
     */
    fun configuredApp() {
        val app = Device.app.packageName
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach { permission ->
            Device.shell("pm grant $app $permission")
        }
        if (settings().server.isConfigured) return
        val key = Device.shell("cat $KEY_FILE").trim()
        check(key.isNotEmpty() && !key.contains(' ')) { "push the gate key to $KEY_FILE first" }
        val pin = InstrumentationRegistry.getArguments().getString(CERT_ARGUMENT)
        checkNotNull(pin) { "pass -e $CERT_ARGUMENT <sha256 hex> (android/scripts/connected-tests.sh does)" }
        settings {
            it.copy(
                server = ServerConfig(
                    address = GATE,
                    key = ServerKey(key),
                    certificate = CertificateFingerprint(pin.lowercase().replace(":", "")),
                ),
            )
        }
    }

    /** The settings now. */
    fun settings(): Settings = call { settings().current() }

    /** Changes the settings and fails when they could not be saved. */
    fun settings(transform: (Settings) -> Settings) {
        val saved = call { settings().update(transform) }
        check(saved is net.jbglenn.telltype.core.common.Outcome.Ok) { "settings not saved" }
    }

    /** Sets the server address, keeping key and certificate. */
    fun address(endpoint: ServerEndpoint) = settings { it.copy(server = it.server.copy(address = endpoint)) }

    /** Every History and Archive entry, newest first. */
    fun history(): List<HistoryEntry> = call { history().recent(HISTORY_LIMIT).first() }

    /** Writes [entry] to History as a finished dictation would. */
    fun addHistory(entry: HistoryEntry) {
        val saved = call { history().upsert(entry) }
        check(saved is net.jbglenn.telltype.core.common.Outcome.Ok) { "history entry not saved" }
    }

    /** Deletes every History and Archive entry. */
    fun clearHistory() {
        call { history().deleteAll() }
    }

    /** The newest History entry, if any. */
    fun latestHistory(): HistoryEntry? = call { history().recent(1).first().firstOrNull() }

    /** The dictations waiting for the server. */
    fun pendingJobs(): List<PendingJob> = call { pendingJobs().jobs.first() }

    /** When the bubble's "hide for 1 hour" snooze ends; null while it is not snoozed. */
    fun snoozedUntil(): kotlin.time.Instant? = call { bubbleSnooze().snoozedUntil.value }

    /** Ends the bubble's snooze. */
    fun resumeSnooze() = call { bubbleSnooze().resume() }

    /** Whether [app] is excluded from dictation ("Never show in this app"). */
    fun isExcluded(app: String): Boolean = call {
        (appRules().find(AppPackage(app)) as? net.jbglenn.telltype.core.common.Outcome.Ok)?.value?.excluded ==
            true
    }

    /** Excludes [app] from dictation, or removes its rule. */
    fun excluded(app: String, excluded: Boolean) {
        call {
            if (excluded) {
                appRules().upsert(
                    AppRule(AppPackage(app), categoryOverride = null, toneOverride = null, excluded = true),
                )
            } else {
                appRules().delete(AppPackage(app))
            }
        }
    }

    /** Sets a style rule for [app]: its category and, optionally, its own tone. */
    fun style(app: String, category: AppCategory, tone: Tone? = null) {
        val saved = call {
            appRules().upsert(
                AppRule(AppPackage(app), categoryOverride = category, toneOverride = tone, excluded = false),
            )
        }
        check(saved is net.jbglenn.telltype.core.common.Outcome.Ok) { "style rule not saved" }
    }

    /** Removes the rule for [app] (style or exclusion). */
    fun removeRule(app: String) {
        call { appRules().delete(AppPackage(app)) }
    }

    /** Adds a dictionary term with its "sounds like" forms. */
    fun addTerm(term: String, soundsLike: List<String> = emptyList()) {
        val saved = call { dictionary().addTerm(term, soundsLike, EntrySource.MANUAL) }
        check(saved is net.jbglenn.telltype.core.common.Outcome.Ok) { "term not saved" }
    }

    /** Deletes every dictionary term and replacement. */
    fun clearDictionary() = call {
        dictionary().terms.first().forEach { dictionary().deleteTerm(it.id) }
        dictionary().replacements.first().forEach { dictionary().deleteReplacement(it.id) }
    }

    /** The dictionary terms now. */
    fun terms(): List<DictionaryTerm> = call { dictionary().terms.first() }

    /** Adds a snippet. */
    fun addSnippet(trigger: String, text: String) {
        val saved = call { snippets().add(trigger, text) }
        check(saved is net.jbglenn.telltype.core.common.Outcome.Ok) { "snippet not saved" }
    }

    /** Deletes every snippet. */
    fun clearSnippets() = call { snippets().snippets.first().forEach { snippets().delete(it.id) } }

    /** The snippets now. */
    fun snippets(): List<Snippet> = call { snippets().snippets.first() }

    private const val HISTORY_LIMIT = 500
    private const val GROUP_HEX = 8
}
