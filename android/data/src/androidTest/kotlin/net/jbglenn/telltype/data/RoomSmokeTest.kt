package net.jbglenn.telltype.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.jbglenn.telltype.core.common.DispatcherProvider
import net.jbglenn.telltype.core.common.Outcome
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.data.db.ExclusionSeed
import net.jbglenn.telltype.data.db.TelltypeDatabase
import net.jbglenn.telltype.data.repository.RoomHistoryRepository
import net.jbglenn.telltype.testing.Fixtures
import net.jbglenn.telltype.testing.RecordingLogger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The real database file on a device: created, seeded, written, searched and read back through a repository. */
@RunWith(AndroidJUnit4::class)
class RoomSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private object DeviceDispatchers : DispatcherProvider {
        override val main = Dispatchers.Main
        override val io = Dispatchers.IO
        override val default = Dispatchers.Default
        override val accessibility = Dispatchers.Default
        override val audio = Dispatchers.Default
    }

    @After
    fun deleteDatabase() {
        context.deleteDatabase(TelltypeDatabase.FILE_NAME)
    }

    @Test
    fun databaseFileWorksEndToEnd() = runTest {
        context.deleteDatabase(TelltypeDatabase.FILE_NAME)
        val database = TelltypeDatabase.open(context, DeviceDispatchers.io)
        try {
            val history = RoomHistoryRepository(database.history(), DeviceDispatchers, RecordingLogger())
            val entry = Fixtures.historyEntry(id = "device-session", finalText = "Hello from the emulator.")

            assertEquals(Outcome.Ok(Unit), history.upsert(entry))
            assertEquals(Outcome.Ok(entry), history.get(SessionId("device-session")))
            assertEquals(listOf(entry), history.search("EMULATOR", 10).first())
            assertEquals(
                ExclusionSeed.Packages.sorted(),
                database.appRules().rules().first().map { it.appPackage },
            )
        } finally {
            database.close()
        }
        assertEquals(true, context.getDatabasePath(TelltypeDatabase.FILE_NAME).exists())
    }
}
