package net.jbglenn.telltype.support

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.di.BundleUrisModule
import net.jbglenn.telltype.domain.port.DebugBundleExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Export writes the bundle and makes its content URI off the main thread (FileProvider reads the disk, which
 * StrictMode punished with a crash in debug builds), then opens the share sheet with that URI.
 */
@HiltAndroidTest
@UninstallModules(BundleUrisModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DebugBundleExportTest {
    @get:Rule
    val hilt = HiltAndroidRule(this)

    private val madeOnMainThread = mutableListOf<Boolean>()

    @BindValue
    @JvmField
    internal val uris: BundleUris = BundleUris { file ->
        synchronized(madeOnMainThread) { madeOnMainThread += Looper.myLooper() == Looper.getMainLooper() }
        Uri.parse("content://bundle.test/${file.name}")
    }

    @Inject
    internal lateinit var exporter: DebugBundleExporter

    @Before
    fun inject() {
        hilt.inject()
    }

    @OptIn(ExperimentalCoroutinesApi::class) // getCompleted: the result of a finished Deferred, without blocking.
    @Test
    fun exportMakesTheUriOffTheMainThreadAndSharesIt() {
        val export = CoroutineScope(Dispatchers.IO).async { exporter.export() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS)
        while (!export.isCompleted && System.nanoTime() < deadline) shadowOf(Looper.getMainLooper()).idle()
        assertTrue("export finished", export.isCompleted)
        assertEquals(Outcome.Ok(Unit), export.getCompleted())
        assertEquals("the URI was made once, off the main thread", listOf(false), madeOnMainThread)
        val app = ApplicationProvider.getApplicationContext<HiltTestApplication>()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        val shared = send?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals(Uri.parse("content://bundle.test/telltype-debug.zip"), shared)
        assertTrue("the bundle file exists", File(app.cacheDir, "exports/telltype-debug.zip").isFile)
    }

    private companion object {
        const val LIMIT_SECONDS = 10L
    }
}
