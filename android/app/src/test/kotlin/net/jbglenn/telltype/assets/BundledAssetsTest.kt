package net.jbglenn.telltype.assets

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jbglenn.telltype.core.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The APK's `shared/` assets parse with the real parsers and carry the versions of the repository's files. */
// A plain Application: TelltypeApp would start the whole app (runtime, WorkManager) around this test.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BundledAssetsTest {
    private val shared = File("../../shared")

    @Test
    fun bundledAssetsParse() {
        val loaded = BundledAssets.load(ApplicationProvider.getApplicationContext<Context>().assets)
        assertTrue("bundled shared/ files do not parse: $loaded", loaded is Outcome.Ok)
    }

    @Test
    fun versionsMatchTheSharedDirectory() {
        val loaded = BundledAssets.load(ApplicationProvider.getApplicationContext<Context>().assets)
        val assets = (loaded as Outcome.Ok).value
        val promptVersion = File(shared, "prompts/VERSION").readText().trim().toInt()
        val guardsVersion = Json.parseToJsonElement(File(shared, "guards.json").readText())
            .jsonObject.getValue("version").jsonPrimitive.int
        assertEquals(promptVersion, assets.prompts.version)
        assertEquals(guardsVersion, assets.guards.version)
    }
}
