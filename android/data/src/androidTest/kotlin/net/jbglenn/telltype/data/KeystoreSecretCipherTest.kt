package net.jbglenn.telltype.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.settings.ServerKey
import net.jbglenn.telltype.data.settings.CipherFailure
import net.jbglenn.telltype.data.settings.DataStoreSettingsRepository
import net.jbglenn.telltype.data.settings.KeystoreSecretCipher
import net.jbglenn.telltype.data.settings.preferencesStore
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real Android Keystore on a device: the server key round-trips, and a wiped key store reads as "no key". */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretCipherTest {
    private val alias = "telltype_test_key_${System.nanoTime()}"
    private val cipher = KeystoreSecretCipher(alias)
    private val secret = "synthetic-instrumented-test-key".encodeToByteArray()
    private val folder = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, alias)

    /** Real dispatchers: this runs on a device, not on virtual time. */
    private object DeviceDispatchers : DispatcherProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val accessibility = Dispatchers.Default
        override val audio = Dispatchers.Default
    }

    private fun wipeKeyStore() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @After
    fun cleanUp() {
        wipeKeyStore()
        folder.listFiles()?.forEach(File::delete)
        folder.delete()
    }

    @Test
    fun sealedSecretOpensWithTheKeystoreKey() {
        val sealed = (cipher.seal(secret) as Outcome.Ok).value

        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("synthetic"))
        assertArrayEquals(secret, (cipher.open(sealed) as Outcome.Ok).value)
        val again = (cipher.seal(secret) as Outcome.Ok).value
        assertFalse("a fresh IV every time", sealed.contentEquals(again))
    }

    @Test
    fun wipedKeyStoreIsReportedNotThrown() {
        val sealed = (cipher.seal(secret) as Outcome.Ok).value
        wipeKeyStore()

        assertEquals(Outcome.Err(CipherFailure.KEY_UNAVAILABLE), cipher.open(sealed))
        cipher.seal(secret)
        assertEquals("a new key cannot open the old value", Outcome.Err(CipherFailure.CORRUPT), cipher.open(sealed))
    }

    @Test
    fun settingsWithAWipedKeyStoreReadAsNoKey() = runTest {
        folder.mkdirs()
        val file = File(folder, "settings.preferences_pb")
        val logger = RecordingLogger()

        val firstJob = Job()
        val first = DataStoreSettingsRepository(
            preferencesStore(file, CoroutineScope(Dispatchers.IO + firstJob), logger),
            cipher,
            DeviceDispatchers,
            logger,
        )
        first.update { it.copy(server = it.server.copy(key = ServerKey("synthetic-instrumented-test-key"))) }
        assertEquals(ServerKey("synthetic-instrumented-test-key"), first.current().server.key)
        firstJob.cancelAndJoin()

        wipeKeyStore()

        val secondJob = Job()
        val second = DataStoreSettingsRepository(
            preferencesStore(file, CoroutineScope(Dispatchers.IO + secondJob), logger),
            cipher,
            DeviceDispatchers,
            logger,
        )
        assertNull(second.current().server.key)
        assertTrue(logger.entries.any { "cannot be opened" in it.message })
        assertFalse(logger.entries.any { "synthetic-instrumented-test-key" in it.message })
        secondJob.cancelAndJoin()
    }
}
