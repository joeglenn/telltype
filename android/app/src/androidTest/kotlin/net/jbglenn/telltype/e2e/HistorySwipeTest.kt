package net.jbglenn.telltype.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import net.jbglenn.telltype.core.model.AppPackage
import net.jbglenn.telltype.core.model.SessionId
import net.jbglenn.telltype.core.model.cleanup.CleanupStatus
import net.jbglenn.telltype.core.model.data.DictationMode
import net.jbglenn.telltype.core.model.data.HistoryEntry
import net.jbglenn.telltype.core.model.insert.InsertedVia
import net.jbglenn.telltype.core.model.session.SessionTimings
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.e2e.Device.await
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Brief A9a item 1 (D52) on a device: in History a swipe right archives an entry and a swipe left deletes one, each
 * with an Undo bar; Undo brings a deleted entry back, and without Undo the deletion is final a few seconds later.
 * The archived entry is in the Archive, reached from the menu with its count. Screenshots `history-swipe-*`.
 */
@RunWith(AndroidJUnit4::class)
class HistorySwipeTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Arrange.clearHistory()
        Arrange.addHistory(entry(KEEP_ID, "Keep this one in the Archive.", minutesAgo = 2))
        Arrange.addHistory(entry(DROP_ID, "Throw this one away.", minutesAgo = 1))
    }

    @After
    fun tearDown() {
        // The archived entry would otherwise survive the next test's "Clear history", which keeps the Archive (D52).
        Arrange.clearHistory()
    }

    @Test
    fun swipeRightArchivesAndSwipeLeftDeletesWithUndo() {
        Device.openApp()
        await(By.text("History"), what = "the navigation bar").click()
        Device.screenshot("history-swipe-1-list")

        row("Keep this one").swipe(Direction.RIGHT, SWIPE_PERCENT)
        await(By.text("Moved to Archive"), what = "the Undo bar of the archive swipe")
        Device.screenshot("history-swipe-2-archived")
        assertTrue("archived", Scenario.eventually { stored(KEEP_ID)?.archived == true })

        row("Throw this one").swipe(Direction.LEFT, SWIPE_PERCENT)
        await(By.text("Dictation deleted"), what = "the Undo bar of the delete swipe")
        Device.screenshot("history-swipe-3-deleted")
        await(By.text("Undo"), what = "Undo").click()
        await(By.textContains("Throw this one"), what = "the entry back after Undo")
        assertTrue("not deleted while Undo was offered", stored(DROP_ID) != null)

        row("Throw this one").swipe(Direction.LEFT, SWIPE_PERCENT)
        await(By.text("Dictation deleted"), what = "the Undo bar again")
        assertTrue(
            "the deletion is final once the Undo bar has gone",
            Scenario.eventually(FINAL_WAIT_MS) { stored(DROP_ID) == null },
        )

        await(By.desc("More options")).click()
        await(By.text("Archive (1)"), what = "the menu's Archive item with its count").click()
        await(By.textContains("Keep this one"), what = "the archived entry in the Archive")
        Device.screenshot("history-swipe-4-archive")
        assertEquals(listOf(SessionId(KEEP_ID)), Arrange.history().map { it.id })
    }

    private fun row(text: String): UiObject2 = await(By.textContains(text), what = "the row \"$text\"")

    private fun stored(id: String): HistoryEntry? = Arrange.history().firstOrNull { it.id == SessionId(id) }

    private fun entry(id: String, text: String, minutesAgo: Int) = HistoryEntry(
        id = SessionId(id),
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()) - minutesAgo.minutes,
        app = AppPackage("net.jbglenn.telltype.testhost"),
        category = AppCategory.OTHER,
        mode = DictationMode.DICTATE,
        finalText = text,
        rawTranscript = text.lowercase(),
        cleanupStatus = CleanupStatus.APPLIED,
        insertedVia = InsertedVia.INPUT_CONNECTION,
        audioDuration = 2.seconds,
        timings = SessionTimings.None,
        errorCode = null,
    )

    private companion object {
        const val KEEP_ID = "a9a-swipe-keep"
        const val DROP_ID = "a9a-swipe-drop"
        const val SWIPE_PERCENT = 0.8f
        const val FINAL_WAIT_MS = 15_000L
    }
}
