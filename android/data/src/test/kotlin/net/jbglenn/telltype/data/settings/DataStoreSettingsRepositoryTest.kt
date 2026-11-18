package net.jbglenn.telltype.data.settings

import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.LogLevel
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.error.StorageFailed
import net.jbglenn.telltype.core.model.settings.CertificateFingerprint
import net.jbglenn.telltype.core.model.settings.ServerConfig
import net.jbglenn.telltype.core.model.settings.ServerEndpoint
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.core.model.settings.Settings
import net.jbglenn.telltype.data.FakeSecretCipher
import net.jbglenn.telltype.data.testDispatchers
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataStoreSettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logger = RecordingLogger()
    private val cipher = FakeSecretCipher()
    private val secret = "synthetic-server-key-for-tests"
    private val configured = ServerConfig(
        address = ServerEndpoint("192.0.2.10", 8170),
        key = ServerKey(secret),
        certificate = CertificateFingerprint("ab".repeat(32)),
    )

    private val file: File get() = File(folder.root, "settings.preferences_pb")

    /** Opens a repository on [file] whose DataStore lives until [stop] is called. */
    private inner class Opened(scope: TestScope) {
        val job = Job()
        val store = preferencesStore(file, CoroutineScope(scope.coroutineContext + job), logger)
        val repository = DataStoreSettingsRepository(store, cipher, scope.testDispatchers(), logger)

        suspend fun stop() = job.cancelAndJoin()
    }

    @Test
    fun `a fresh install reads the defaults`() = runTest {
        val opened = Opened(this)

        assertEquals(Settings.Defaults, opened.repository.current())
        opened.stop()
    }

    @Test
    fun `an update is applied, returned, emitted and survives reopening`() = runTest {
        val opened = Opened(this)
        opened.repository.settings.test {
            assertEquals(Settings.Defaults, awaitItem())
            val result = opened.repository.update { it.copy(server = configured) }
            assertEquals(Outcome.Ok(Settings.Defaults.copy(server = configured)), result)
            assertEquals(configured, awaitItem().server)
            cancelAndIgnoreRemainingEvents()
        }
        opened.stop()

        val reopened = Opened(this)
        assertEquals(Settings.Defaults.copy(server = configured), reopened.repository.current())
        reopened.stop()
    }

    @Test
    fun `the key is stored sealed, never in plain text`() = runTest {
        val opened = Opened(this)
        opened.repository.update { it.copy(server = configured) }

        val sealed = opened.store.data.first()[stringPreferencesKey(SettingsCodec.SEALED_KEY)]
        assertNotNull(sealed)
        assertFalse(sealed!!.contains(secret))
        assertFalse(file.readBytes().decodeToString().contains(secret))
        assertEquals(1, cipher.sealCount.get())
        opened.repository.update { it.copy(bubble = it.bubble.copy(haptics = false)) }
        assertEquals("an unchanged key is not sealed again", 1, cipher.sealCount.get())
        opened.stop()
    }

    @Test
    fun `a key that can no longer be opened reads as no key, not a crash`() = runTest {
        val opened = Opened(this)
        opened.repository.update { it.copy(server = configured) }
        opened.stop()
        cipher.wipe()

        val reopened = Opened(this)
        val settings = reopened.repository.current()

        assertNull(settings.server.key)
        assertEquals(configured.address, settings.server.address)
        assertTrue(logger.entries.any { it.level == LogLevel.WARN && "cannot be opened" in it.message })
        assertFalse(logger.entries.any { secret in it.message })
        val repaired = reopened.repository.update { it.copy(server = it.server.copy(key = ServerKey("new-key"))) }
        assertEquals(ServerKey("new-key"), (repaired as Outcome.Ok).value.server.key)
        assertEquals(ServerKey("new-key"), reopened.repository.current().server.key)
        reopened.stop()
    }

    @Test
    fun `a key that cannot be sealed fails the update and changes nothing`() = runTest {
        val opened = Opened(this)
        cipher.failSealing = true

        val result = opened.repository.update { it.copy(server = configured, bubble = it.bubble.copy(enabled = false)) }

        assertEquals(Outcome.Err(StorageFailed), result)
        assertEquals(Settings.Defaults, opened.repository.current())
        assertFalse(logger.entries.any { secret in it.message })
        opened.stop()
    }

    @Test
    fun `removing the key removes the sealed value`() = runTest {
        val opened = Opened(this)
        opened.repository.update { it.copy(server = configured) }

        opened.repository.update { it.copy(server = it.server.copy(key = null)) }

        assertNull(opened.store.data.first()[stringPreferencesKey(SettingsCodec.SEALED_KEY)])
        assertNull(opened.repository.current().server.key)
        opened.stop()
    }

    @Test
    fun `a corrupted file is replaced by the defaults and logged`() = runTest {
        file.writeBytes(byteArrayOf(0x0a, 0x7f, 0x13, 0x00, 0x55, 0x2b, 0x01))
        val opened = Opened(this)

        assertEquals(Settings.Defaults, opened.repository.current())
        assertTrue(logger.entries.any { it.level == LogLevel.ERROR && "corrupted" in it.message })
        assertEquals(
            Outcome.Ok(Settings.Defaults.copy(server = configured)),
            opened.repository.update {
                it.copy(server = configured)
            },
        )
        opened.stop()
    }
}
