package net.jbglenn.telltype.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import net.jbglenn.telltype.core.common.LogTag
import net.jbglenn.telltype.core.common.Logger
import net.jbglenn.telltype.core.common.error

/**
 * A Preferences DataStore at [file] whose corrupted file is replaced by an empty one (so every setting reads as its
 * default) and logged as an error, instead of failing every read. [scope] owns the store's I/O and lives as long
 * as the app (or the test).
 */
internal fun preferencesStore(file: File, scope: CoroutineScope, logger: Logger): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { failure: CorruptionException ->
            logger.error(TAG, failure) { "${file.name} was corrupted and has been replaced by defaults" }
            emptyPreferences()
        },
        scope = scope,
        produceFile = { file },
    )

private val TAG = LogTag("PreferencesStore")
