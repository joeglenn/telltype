package net.jbglenn.telltype.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.data.db.TelltypeDatabase
import net.jbglenn.telltype.data.db.configure
import net.jbglenn.telltype.data.settings.CipherFailure
import net.jbglenn.telltype.data.settings.SecretCipher
import net.jbglenn.telltype.data.work.PendingWorkScheduler
import net.jbglenn.telltype.testing.TestDispatcherProvider

/** The application context Robolectric provides. */
internal fun context(): Context = ApplicationProvider.getApplicationContext()

/** All dispatchers on this test's scheduler, so storage work runs on virtual time. */
internal fun TestScope.testDispatchers(): TestDispatcherProvider =
    TestDispatcherProvider(StandardTestDispatcher(testScheduler))

/**
 * An in-memory database configured like the real one (seed, migrations, query context). Robolectric runs the test
 * on the main looper thread, so main-thread queries are allowed here only.
 */
internal fun TestScope.inMemoryDatabase(dispatchers: TestDispatcherProvider = testDispatchers()): TelltypeDatabase =
    Room.inMemoryDatabaseBuilder(context(), TelltypeDatabase::class.java)
        .configure(dispatchers.io)
        .allowMainThreadQueries()
        .build()

/**
 * A reversible stand-in for the Keystore cipher: XOR with a fixed byte plus a marker, so a sealed value never
 * contains the plaintext. [wipe] simulates a wiped key store; [failSealing] a crypto failure.
 */
internal class FakeSecretCipher : SecretCipher {
    var wiped = false
    var failSealing = false
    val sealCount = AtomicInteger()

    fun wipe() {
        wiped = true
    }

    override fun seal(plaintext: ByteArray): Outcome<ByteArray, CipherFailure> {
        if (failSealing) return Outcome.Err(CipherFailure.FAILED)
        sealCount.incrementAndGet()
        wiped = false
        return Outcome.Ok(byteArrayOf(MARKER) + plaintext.map { (it.toInt() xor MASK).toByte() })
    }

    override fun open(sealed: ByteArray): Outcome<ByteArray, CipherFailure> = when {
        wiped -> Outcome.Err(CipherFailure.KEY_UNAVAILABLE)
        sealed.isEmpty() || sealed[0] != MARKER -> Outcome.Err(CipherFailure.CORRUPT)
        else -> Outcome.Ok(sealed.drop(1).map { (it.toInt() xor MASK).toByte() }.toByteArray())
    }

    private companion object {
        const val MARKER: Byte = 0x5a
        const val MASK = 0x2c
    }
}

/** Counts scheduling calls instead of talking to WorkManager. */
internal class FakeScheduler(var available: Boolean = true) : PendingWorkScheduler {
    var pendingJobsScheduled = 0
    var retentionScheduled = 0

    override fun schedulePendingJobs(): Boolean {
        pendingJobsScheduled++
        return available
    }

    override fun scheduleRetention(): Boolean {
        retentionScheduled++
        return available
    }
}
