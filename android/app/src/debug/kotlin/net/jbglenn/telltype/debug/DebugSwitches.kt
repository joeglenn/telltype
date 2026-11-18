package net.jbglenn.telltype.debug

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.withContext
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.domain.port.SettingsRepository

/**
 * Debug builds: which fake parts are on. "Use fake engines" (Settings > Advanced) switches on all of them. The clip
 * microphone alone (real engines, for dictating against the server on an emulator without microphone) is switched on by
 * the marker file `no_backup/debug/clip-microphone`, which the instrumented tests create (or
 * `adb shell run-as <app id> touch no_backup/debug/clip-microphone`); its content may name the clip to play
 * (`SampleClip.assetFor`). The marker file `no_backup/debug/fake-engines`
 * switches the fake engines on like the setting does, without touching the settings the connected tests rely on.
 */
internal class DebugSwitches @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val dispatchers: DispatcherProvider,
) {

    /** The fake speech engine and cleaner are used. Safe on any thread: the marker is read on the I/O dispatcher. */
    suspend fun fakeEngines(): Boolean = marker(FAKE_ENGINES_MARKER) || settings.current().advanced.useFakeEngines

    /** The microphone plays the bundled clip. */
    suspend fun clipMicrophone(): Boolean = marker(CLIP_MICROPHONE_MARKER) || fakeEngines()

    /** The text of the marker file [name] (relative to `noBackupFilesDir`), null when it does not exist. */
    suspend fun markerText(name: String): String? = withContext(dispatchers.io) {
        File(context.noBackupFilesDir, name).takeIf(File::isFile)?.readText()
    }

    private suspend fun marker(name: String): Boolean =
        withContext(dispatchers.io) { File(context.noBackupFilesDir, name).isFile }

    companion object {
        /** The marker file, relative to `noBackupFilesDir`. */
        const val CLIP_MICROPHONE_MARKER = "debug/clip-microphone"

        /** The marker file that switches the fake engines on, relative to `noBackupFilesDir`. */
        const val FAKE_ENGINES_MARKER = "debug/fake-engines"
    }
}
