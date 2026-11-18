package net.jbglenn.telltype.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jbglenn.telltype.BuildConfig
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.error.StorageError
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.crash.CrashReporter
import net.jbglenn.telltype.domain.port.DebugBundleExporter
import net.jbglenn.telltype.domain.port.HistoryRepository
import net.jbglenn.telltype.domain.port.SettingsRepository
import net.jbglenn.telltype.logging.LogFile

/**
 * The Diagnostics debug bundle (docs/PLAN.md section 13): a zip of the log files, crash reports, misrecognition
 * reports, the timings of the recent dictations (ids, apps, statuses and durations, never their text) and the
 * settings (the key prints only as redacted), shared through the system share sheet with a `FileProvider` URI.
 */
internal class ZipDebugBundleExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uris: BundleUris,
    private val sources: BundleSources,
    private val dispatchers: DispatcherProvider,
) : DebugBundleExporter {
    override suspend fun export(): Outcome<Unit, StorageError> {
        // The content URI is made on the IO dispatcher as well: FileProvider reads the disk to resolve it.
        val uri = withContext(dispatchers.io) { write()?.let(uris::uriFor) } ?: return Outcome.Err(StorageFailed)
        return withContext(dispatchers.main) { share(uri) }
    }

    private suspend fun write(): File? {
        val texts = mapOf(
            "settings.txt" to sources.settingsText(),
            "timings.tsv" to sources.timingsText(),
            "app.txt" to "Telltype ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n",
        )
        val files = sources.files()
        val target = File(context.cacheDir, "$DIRECTORY/$BUNDLE_NAME")
        return try {
            target.parentFile?.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { zip -> zip.add(texts, files) }
            target
        } catch (_: IOException) {
            null
        }
    }

    private fun ZipOutputStream.add(texts: Map<String, String>, files: List<Pair<String, File>>) {
        texts.forEach { (name, text) ->
            putNextEntry(ZipEntry(name))
            write(text.toByteArray())
            closeEntry()
        }
        files.forEach { (name, file) ->
            putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(this) }
            closeEntry()
        }
    }

    private fun share(uri: Uri): Outcome<Unit, StorageError> {
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            Outcome.Ok(Unit)
        } catch (_: ActivityNotFoundException) {
            Outcome.Err(StorageFailed)
        }
    }

    private companion object {
        const val DIRECTORY = "exports"
        const val BUNDLE_NAME = "telltype-debug.zip"
        const val MIME_TYPE = "application/zip"
    }
}

/**
 * What goes into the debug bundle: the settings and recent timings as plain text without dictated text or the key,
 * and the log files, crash reports and misrecognition reports.
 */
internal class BundleSources @Inject constructor(
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val log: LogFile,
    private val crashes: CrashReporter,
    private val reports: FileMisrecognitionReporter,
) {
    /** The files to copy, with their names in the zip. Reads the disk: call off the main thread. */
    fun files(): List<Pair<String, File>> = log.files().map { "logs/${it.name}" to it } +
        crashes.reports().map { "crash/${it.name}" to it } +
        listOfNotNull(reports.reportFile()?.let { "reports/${it.name}" to it })

    /** `Settings.toString()`: the key prints only as redacted. */
    suspend fun settingsText(): String = settings.current().toString() + "\n"

    /** One line per recent dictation: ids, app, status and the stage durations. */
    suspend fun timingsText(): String {
        val entries = withTimeoutOrNull(READ_LIMIT) { history.recent(RECENT).first() }.orEmpty()
        return buildString {
            appendLine("session\tcreated\tapp\tcleanup\tvia\terror\trecording\ttranscription\tcleanup_ms\ttotal")
            entries.forEach { appendLine(it.timingLine()) }
        }
    }

    private fun HistoryEntry.timingLine(): String = listOf(
        id.value,
        createdAt.toString(),
        app.name,
        cleanupStatus?.id.orEmpty(),
        insertedVia.id,
        errorCode.orEmpty(),
        timings.recording?.inWholeMilliseconds?.toString().orEmpty(),
        timings.transcription?.inWholeMilliseconds?.toString().orEmpty(),
        timings.cleanup?.inWholeMilliseconds?.toString().orEmpty(),
        timings.endToEnd?.inWholeMilliseconds?.toString().orEmpty(),
    ).joinToString("\t")

    private companion object {
        const val RECENT = 50
        val READ_LIMIT = 5.seconds
    }
}

/** Makes the shareable content URI of a file in the app's cache. Reads the disk: call it off the main thread. */
internal fun interface BundleUris {
    /** The `FileProvider` URI of [file]. */
    fun uriFor(file: File): Uri
}

/** [BundleUris] through the app's `FileProvider` (authority `<package>.files`). */
internal class FileProviderBundleUris @Inject constructor(@ApplicationContext private val context: Context) :
    BundleUris {
    override fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

    private companion object {
        const val AUTHORITY_SUFFIX = ".files"
    }
}
