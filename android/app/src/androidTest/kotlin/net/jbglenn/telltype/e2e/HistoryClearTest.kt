package net.jbglenn.telltype.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import net.jbglenn.telltype.e2e.Device.await
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Brief A8b item 1: a dictation (fake engines, clip microphone) lands in History; \"Clear history\" in History's
 * menu asks first, then empties History, which shows its empty state. Screenshots `history-clear-*`.
 */
@RunWith(AndroidJUnit4::class)
class HistoryClearTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        Device.useClipMicrophone()
        Bubble.fakeEngines(true)
    }

    @After
    fun tearDown() {
        Bubble.fakeEngines(false)
    }

    @Test
    fun aDictationStaysInHistoryUntilHistoryIsCleared() {
        Scenario.field()
        Scenario.dictate()
        assertTrue("the dictation was typed", Scenario.awaitFieldText(Scenario.PHOEBE))
        assertTrue(
            "the dictation was saved to History",
            Scenario.eventually { Arrange.latestHistory()?.finalText?.contains("Phoebe") == true },
        )

        Device.openApp()
        await(By.text("History"), what = "the navigation bar").click()
        await(By.textContains("Phoebe"), what = "the dictation in History")
        Device.screenshot("history-clear-1-list")
        await(By.desc("More options")).click()
        await(By.text("Clear history"), what = "the menu item").click()
        await(By.textContains("cannot be undone"), what = "the confirmation")
        Device.screenshot("history-clear-2-confirm")
        await(By.text("Clear history"), what = "the confirming button").click()

        await(By.text("No dictations yet"), what = "History's empty state")
        Device.screenshot("history-clear-3-empty")
        assertNull("History holds no entry", Arrange.latestHistory())

        await(By.text("Settings"), what = "the navigation bar").click()
        Device.awaitScrolling(By.text("Clear history now"))
        await(By.text("History is empty"), what = "the Privacy row's count")
        Device.screenshot("history-clear-4-privacy")
    }
}
