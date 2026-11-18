package net.jbglenn.telltype.startup

import javax.inject.Inject
import javax.inject.Provider
import net.jbglenn.telltype.data.settings.ModelNameMigration
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * What [AppStartup] needs of the settings: the repository (for the verbose-log switch) and the migration that
 * replaces the deprecated model aliases after a successful check (brief M1).
 */
internal class StartupSettings @Inject constructor(
    val repository: Provider<SettingsRepository>,
    val migration: Provider<ModelNameMigration>,
)
