package net.jbglenn.telltype.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.common.error
import net.jbglenn.telltype.core.common.warn
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.data.storage.storageErrorOf
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * [SettingsRepository] on a Preferences DataStore ([SettingsCodec]). The server key is sealed with [cipher] (a
 * Keystore AES-GCM key) and stored as Base64; a key that cannot be opened any more (key store wiped) reads as "no
 * key" and is logged, never a crash. A read failure gives the defaults, logged; a corrupted file is replaced by
 * defaults by the DataStore's corruption handler ([preferencesStore]). All work runs on the io dispatcher.
 */
internal class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : SettingsRepository {
    private val sealedKey = stringPreferencesKey(SettingsCodec.SEALED_KEY)

    @Volatile
    private var openedKey: Pair<String, ServerKey?>? = null

    override val settings: Flow<Settings> = store.data
        .catch { failure ->
            if (failure !is IOException) throw failure
            logger.error(TAG, failure) { "settings could not be read; using defaults" }
            emit(emptyPreferences())
        }
        .map(::decode)
        .distinctUntilChanged()
        .flowOn(dispatchers.io)

    override suspend fun current(): Settings = settings.first()

    override suspend fun update(transform: (Settings) -> Settings): Outcome<Settings, StorageError> =
        withContext(dispatchers.io) {
            try {
                var updated = Settings.Defaults
                store.edit { preferences ->
                    val current = decode(preferences)
                    val next = transform(current)
                    SettingsCodec.encode(next, preferences)
                    writeKey(preferences, current.server.key, next.server.key)
                    updated = next
                }
                Outcome.Ok(updated)
            } catch (failure: IOException) {
                val error = storageErrorOf(failure)
                logger.error(TAG, failure) { "settings could not be saved: ${error.code}" }
                Outcome.Err(error)
            } catch (failure: KeySealingFailed) {
                logger.error(TAG, null) { "settings not saved: the server key could not be sealed (${failure.reason})" }
                Outcome.Err(StorageFailed)
            }
        }

    private fun decode(preferences: Preferences): Settings {
        val values = preferences.asMap().mapKeys { it.key.name }
        val (settings, problems) = SettingsCodec.decode(values, openKey(preferences[sealedKey]))
        if (problems.isNotEmpty()) logger.warn(TAG, null) { "invalid settings replaced by defaults: $problems" }
        return settings
    }

    private fun writeKey(preferences: MutablePreferences, before: ServerKey?, after: ServerKey?) {
        when {
            after == null -> preferences.remove(sealedKey)

            after == before -> Unit

            else -> preferences[sealedKey] = when (val sealed = cipher.seal(after.secret.encodeToByteArray())) {
                is Outcome.Ok -> Base64.encode(sealed.value)
                is Outcome.Err -> throw KeySealingFailed(sealed.error)
            }
        }
    }

    /** Opens the stored key, remembering the last result so the Keystore is not asked on every emission. */
    private fun openKey(stored: String?): ServerKey? {
        val remembered = openedKey?.takeIf { it.first == stored }
        if (stored == null || remembered != null) return remembered?.second
        val bytes = try {
            Base64.decode(stored)
        } catch (_: IllegalArgumentException) {
            null
        }
        val key = when (val opened = bytes?.let(cipher::open)) {
            is Outcome.Ok -> ServerKey(opened.value.decodeToString())

            is Outcome.Err -> {
                logger.warn(TAG, null) {
                    "the stored server key cannot be opened (${opened.error}); treated as not set"
                }
                null
            }

            null -> {
                logger.warn(TAG, null) { "the stored server key is not Base64; treated as not set" }
                null
            }
        }
        openedKey = stored to key
        return key
    }

    /** Thrown inside a DataStore transaction to abort it when the key cannot be sealed. */
    private class KeySealingFailed(val reason: CipherFailure) : Exception("server key could not be sealed: $reason")

    private companion object {
        val TAG = LogTag("SettingsRepo")
    }
}
