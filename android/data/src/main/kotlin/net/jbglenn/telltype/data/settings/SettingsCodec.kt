package net.jbglenn.telltype.data.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.jbglenn.telltype.core.model.settings.AdvancedSettings
import net.jbglenn.telltype.core.model.settings.BubbleSettings
import net.jbglenn.telltype.core.model.settings.BubbleSide
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.CleanupSettings
import net.jbglenn.telltype.core.model.settings.ContextSettings
import net.jbglenn.telltype.core.model.settings.DictationSettings
import net.jbglenn.telltype.core.model.settings.DictionarySettings
import net.jbglenn.telltype.core.model.settings.InsertionSettings
import net.jbglenn.telltype.core.model.settings.LearnMode
import net.jbglenn.telltype.core.model.settings.PrivacySettings
import net.jbglenn.telltype.core.model.settings.Scheme
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.ServerModels
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.core.model.settings.StyleSettings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone

/**
 * The DataStore representation of [Settings] (Appendix G): one preference per setting, named by group. Reading
 * is tolerant: a missing key gives the Appendix G default, a key of the wrong type or with an invalid value gives
 * the default and is reported by name (never by value), unknown keys are ignored. The server key is not here: it
 * is stored sealed under [SEALED_KEY] by the repository.
 */
internal object SettingsCodec {
    /** The sealed server key, Base64. */
    const val SEALED_KEY = "server.key_sealed"

    private const val ADDRESS = "server.address"
    private const val ENDPOINTS = "server.endpoints"
    private const val CERTIFICATE = "server.certificate_sha256"
    private const val TEXT_ADDRESS = "server.text_address"
    private const val TEXT_CERTIFICATE = "server.text_certificate_sha256"
    private const val SPEECH_MODEL = "server.model.speech"
    private const val CLEANUP_MODEL = "server.model.cleanup"
    private const val COMMAND_MODEL = "server.model.command"
    private const val BUBBLE_ENABLED = "bubble.enabled"
    private const val BUBBLE_SIDE = "bubble.side"
    private const val BUBBLE_HAPTICS = "bubble.haptics"
    private const val BUBBLE_SOUND = "bubble.sound_cues"
    private const val SESSION_LIMIT = "dictation.session_limit_ms"
    private const val PAUSE_AUDIO = "dictation.pause_other_audio"
    private const val CLEANUP_ENABLED = "cleanup.enabled"
    private const val MODEL_DEADLINE = "cleanup.model_deadline_ms"
    private const val SKIP_SHORT = "cleanup.skip_short_phrases"
    private const val STYLE_PREFIX = "styles."
    private const val READ_CONTEXT = "context.read_around_cursor"
    private const val CHARS_BEFORE = "context.chars_before"
    private const val CHARS_AFTER = "context.chars_after"
    private const val LEARN_MODE = "dictionary.learn_from_corrections"
    private const val NEVER_CLIPBOARD = "insertion.never_use_clipboard"
    private const val RETENTION = "privacy.history_retention_ms"
    private const val SAVE_HISTORY = "privacy.save_history"
    private const val VERBOSE_UNTIL = "advanced.verbose_logging_until_ms"
    private const val FAKE_ENGINES = "advanced.use_fake_engines"

    /** Stored for "always wait" ([Duration.INFINITE]) in [MODEL_DEADLINE]. */
    private const val ALWAYS_WAIT = -1L
    private const val MINIMUM_SESSION_LIMIT = 60_000L

    /**
     * Reads settings from DataStore [values] (preference name to value) with the already opened [key]. Returns
     * the settings and the names of the preferences that were invalid and replaced by defaults.
     */
    fun decode(values: Map<String, Any?>, key: ServerKey?): Pair<Settings, List<String>> {
        val read = Reader(values)
        val defaults = Settings.Defaults
        val settings = Settings(
            server = ServerConfig(
                address = address(read, ADDRESS, legacy = true),
                key = key,
                certificate = fingerprint(read, CERTIFICATE),
                textAddress = address(read, TEXT_ADDRESS, legacy = false),
                textCertificate = fingerprint(read, TEXT_CERTIFICATE),
                models = ServerModels(
                    speech = read.string(SPEECH_MODEL)?.takeIf(String::isNotBlank),
                    cleanup = read.string(CLEANUP_MODEL)?.takeIf(String::isNotBlank),
                    command = read.string(COMMAND_MODEL)?.takeIf(String::isNotBlank),
                ),
            ),
            bubble = BubbleSettings(
                enabled = read.bool(BUBBLE_ENABLED, defaults.bubble.enabled),
                side = read.enum(BUBBLE_SIDE, BubbleSide.entries, defaults.bubble.side),
                haptics = read.bool(BUBBLE_HAPTICS, defaults.bubble.haptics),
                soundCues = read.bool(BUBBLE_SOUND, defaults.bubble.soundCues),
                look = BubbleLookCodec.decode(values, read.problems),
            ),
            dictation = DictationSettings(
                sessionLimit = read.long(SESSION_LIMIT, defaults.dictation.sessionLimit.inWholeMilliseconds) {
                    it > MINIMUM_SESSION_LIMIT
                }.milliseconds,
                pauseOtherAudio = read.bool(PAUSE_AUDIO, defaults.dictation.pauseOtherAudio),
            ),
            cleanup = CleanupSettings(
                enabled = read.bool(CLEANUP_ENABLED, defaults.cleanup.enabled),
                modelDeadline = durationOrAlwaysWait(
                    read.long(MODEL_DEADLINE, defaults.cleanup.modelDeadline.inWholeMilliseconds) {
                        it > 0 || it == ALWAYS_WAIT
                    },
                ),
                skipShortPhrases = read.bool(SKIP_SHORT, defaults.cleanup.skipShortPhrases),
            ),
            styles = styles(read, defaults.styles),
            context = ContextSettings(
                readAroundCursor = read.bool(READ_CONTEXT, defaults.context.readAroundCursor),
                charsBefore = read.int(CHARS_BEFORE, defaults.context.charsBefore),
                charsAfter = read.int(CHARS_AFTER, defaults.context.charsAfter),
            ),
            dictionary = DictionarySettings(
                read.enum(LEARN_MODE, LearnMode.entries, defaults.dictionary.learnFromCorrections),
            ),
            insertion = InsertionSettings(read.bool(NEVER_CLIPBOARD, defaults.insertion.neverUseClipboard)),
            privacy = privacy(read, defaults.privacy),
            advanced = AdvancedSettings(
                verboseLoggingUntil = read.optionalLong(VERBOSE_UNTIL)?.let(Instant::fromEpochMilliseconds),
                useFakeEngines = read.bool(FAKE_ENGINES, defaults.advanced.useFakeEngines),
            ),
        )
        return settings to read.problems
    }

    /** Writes every setting except the key into [preferences]. */
    fun encode(settings: Settings, preferences: MutablePreferences) {
        val server = settings.server
        preferences.putOrRemove(ADDRESS, server.address?.let(::endpointJson))
        preferences.remove(stringPreferencesKey(ENDPOINTS))
        preferences.putOrRemove(CERTIFICATE, server.certificate?.sha256Hex)
        preferences.putOrRemove(TEXT_ADDRESS, server.textAddress?.let(::endpointJson))
        preferences.putOrRemove(TEXT_CERTIFICATE, server.textCertificate?.sha256Hex)
        preferences.putOrRemove(SPEECH_MODEL, server.models.speech)
        preferences.putOrRemove(CLEANUP_MODEL, server.models.cleanup)
        preferences.putOrRemove(COMMAND_MODEL, server.models.command)
        preferences[booleanPreferencesKey(BUBBLE_ENABLED)] = settings.bubble.enabled
        preferences[stringPreferencesKey(BUBBLE_SIDE)] = settings.bubble.side.name
        preferences[booleanPreferencesKey(BUBBLE_HAPTICS)] = settings.bubble.haptics
        preferences[booleanPreferencesKey(BUBBLE_SOUND)] = settings.bubble.soundCues
        BubbleLookCodec.encode(settings.bubble.look, preferences)
        preferences[longPreferencesKey(SESSION_LIMIT)] = settings.dictation.sessionLimit.inWholeMilliseconds
        preferences[booleanPreferencesKey(PAUSE_AUDIO)] = settings.dictation.pauseOtherAudio
        preferences[booleanPreferencesKey(CLEANUP_ENABLED)] = settings.cleanup.enabled
        preferences[longPreferencesKey(MODEL_DEADLINE)] = settings.cleanup.modelDeadline
            .let { if (it.isInfinite()) ALWAYS_WAIT else it.inWholeMilliseconds }
        preferences[booleanPreferencesKey(SKIP_SHORT)] = settings.cleanup.skipShortPhrases
        for (category in AppCategory.entries) {
            preferences[stringPreferencesKey(STYLE_PREFIX + category.id)] = settings.styles.toneFor(category).id
        }
        preferences[booleanPreferencesKey(READ_CONTEXT)] = settings.context.readAroundCursor
        preferences[intPreferencesKey(CHARS_BEFORE)] = settings.context.charsBefore
        preferences[intPreferencesKey(CHARS_AFTER)] = settings.context.charsAfter
        preferences[stringPreferencesKey(LEARN_MODE)] = settings.dictionary.learnFromCorrections.name
        preferences[booleanPreferencesKey(NEVER_CLIPBOARD)] = settings.insertion.neverUseClipboard
        preferences[longPreferencesKey(RETENTION)] = settings.privacy.historyRetention.inWholeMilliseconds
        preferences[booleanPreferencesKey(SAVE_HISTORY)] = settings.privacy.saveHistory
        settings.advanced.verboseLoggingUntil?.let {
            preferences[longPreferencesKey(VERBOSE_UNTIL)] =
                it.toEpochMilliseconds()
        }
            ?: preferences.remove(longPreferencesKey(VERBOSE_UNTIL))
        preferences[booleanPreferencesKey(FAKE_ENGINES)] = settings.advanced.useFakeEngines
    }

    private fun durationOrAlwaysWait(millis: Long): Duration = if (millis ==
        ALWAYS_WAIT
    ) {
        Duration.INFINITE
    } else {
        millis.milliseconds
    }

    private fun fingerprint(read: Reader, name: String): CertificateFingerprint? = read.string(name)?.let { hex ->
        validOrNull { CertificateFingerprint(hex) }
            ?: read.invalid(name, null)
    }

    private fun styles(read: Reader, defaults: StyleSettings): StyleSettings {
        fun tone(category: AppCategory): Tone =
            read.typed(STYLE_PREFIX + category.id, defaults.toneFor(category)) { id ->
                Tone.entries.firstOrNull { it.id == id }
            }
        return StyleSettings(
            email = tone(AppCategory.EMAIL),
            workChat = tone(AppCategory.WORK_CHAT),
            personalChat = tone(AppCategory.PERSONAL_CHAT),
            notesDocuments = tone(AppCategory.NOTES_DOCUMENTS),
            aiChat = tone(AppCategory.AI_CHAT),
            code = tone(AppCategory.CODE),
            browser = tone(AppCategory.BROWSER),
            other = tone(AppCategory.OTHER),
        )
    }

    /**
     * The Privacy group. Before A9a "off" was a retention of zero and there was no switch: without [SAVE_HISTORY]
     * the switch reads as on only for a positive retention (D53).
     */
    private fun privacy(read: Reader, defaults: PrivacySettings): PrivacySettings {
        val retention =
            read.long(RETENTION, defaults.historyRetention.inWholeMilliseconds) { it >= 0 }.milliseconds
        return PrivacySettings(
            historyRetention = retention,
            saveHistory = read.bool(SAVE_HISTORY, retention.isPositive()),
        )
    }

    /**
     * The address stored under [name] (D55, D60). Installs before A9a stored an ordered list under [ENDPOINTS]:
     * when [legacy], they keep its first address until the settings are next written, which replaces the list.
     */
    private fun address(read: Reader, name: String, legacy: Boolean): ServerEndpoint? {
        val json = read.string(name) ?: return if (legacy) endpoints(read).firstOrNull() else null
        val entry = try {
            Json.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        return entry?.let(::endpointOf) ?: read.invalid(name, null)
    }

    private fun endpointOf(entry: JsonObject): ServerEndpoint? {
        val host = (entry["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val port = (entry["port"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val schemeId = (entry["scheme"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: Scheme.HTTPS.id
        val scheme = Scheme.entries.firstOrNull { it.id == schemeId }
        return if (host == null || port == null || scheme == null) {
            null
        } else {
            validOrNull { ServerEndpoint(host, port, scheme) }
        }
    }

    private fun endpoints(read: Reader): List<ServerEndpoint> {
        val array = read.string(ENDPOINTS)?.let { json ->
            try {
                Json.parseToJsonElement(json) as? JsonArray
            } catch (_: SerializationException) {
                null
            } ?: read.invalid(ENDPOINTS, JsonArray(emptyList()))
        } ?: JsonArray(emptyList())
        val endpoints = array.mapNotNull { element -> (element as? JsonObject)?.let(::endpointOf) }
        if (endpoints.size != array.size) read.invalid(ENDPOINTS, Unit)
        return endpoints
    }

    /** The value [build] makes, or null when its `require` rejects the input. */
    private inline fun <T> validOrNull(build: () -> T): T? = try {
        build()
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Typed, tolerant reads; every replaced value is recorded in [problems] by preference name. */
    private class Reader(private val values: Map<String, Any?>) {
        val problems = mutableListOf<String>()

        fun bool(name: String, default: Boolean): Boolean = typed(name, default) { it as? Boolean }

        fun int(name: String, default: Int): Int = typed(name, default) { (it as? Int)?.takeIf { value -> value >= 0 } }

        fun long(name: String, default: Long, valid: (Long) -> Boolean): Long =
            typed(name, default) { (it as? Long)?.takeIf(valid) }

        fun optionalLong(name: String): Long? = values[name]?.let { it as? Long ?: invalid(name, null) }

        fun string(name: String): String? = values[name]?.let { it as? String ?: invalid(name, null) }

        fun <E : Enum<E>> enum(name: String, entries: List<E>, default: E): E =
            typed(name, default) { stored -> entries.firstOrNull { it.name == stored } }

        /** The stored value of [name] read by [read]; [default] when absent, or when unreadable (reported). */
        inline fun <T : Any> typed(name: String, default: T, read: (Any) -> T?): T {
            val stored = values[name] ?: return default
            return read(stored) ?: invalid(name, default)
        }

        /** Reports [name] as invalid and returns [fallback]. */
        fun <T> invalid(name: String, fallback: T): T {
            problems += name
            return fallback
        }
    }
}

private fun MutablePreferences.putOrRemove(name: String, value: String?) {
    if (value == null) remove(stringPreferencesKey(name)) else this[stringPreferencesKey(name)] = value
}

/** `{"host":…,"port":…}`, plus `"scheme":"http"` for plain HTTP (an address without it is HTTPS). */
private fun endpointJson(endpoint: ServerEndpoint): String = buildJsonObject {
    put("host", endpoint.host)
    put("port", endpoint.port)
    if (endpoint.scheme != Scheme.HTTPS) put("scheme", endpoint.scheme.id)
}.toString()
