package net.jbglenn.telltype.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import net.jbglenn.telltype.core.model.style.AppCategory
import net.jbglenn.telltype.core.model.style.Tone
import net.jbglenn.telltype.e2e.Device.HOST
import net.jbglenn.telltype.e2e.Device.await
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Package V1: the feature screens show what the features use. A dictionary term with its "sounds like" form, a
 * snippet and a per-app style are arranged; Dictionary, Snippets and Settings > Styles list them. Screenshots
 * `v1-screen-*` for the contact sheet.
 */
@RunWith(AndroidJUnit4::class)
class FeatureScreensTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Arrange.clearDictionary()
        Arrange.clearSnippets()
        Arrange.addTerm("Dagster", listOf("Daxter"))
        Arrange.addSnippet("my address", "221B Baker Street, London NW1 6XE")
        Arrange.settings { it.copy(styles = it.styles.copy(email = Tone.FORMAL, personalChat = Tone.VERY_CASUAL)) }
        Arrange.style(HOST, AppCategory.EMAIL)
    }

    @After
    fun tearDown() {
        Arrange.clearDictionary()
        Arrange.clearSnippets()
        Arrange.removeRule(HOST)
    }

    @Test
    fun dictionarySnippetsAndStylesListWhatTheFeaturesUse() {
        Device.openApp()
        await(By.text("Dictionary"), what = "the navigation bar").click()
        await(By.text("Dagster"), what = "the term")
        await(By.textContains("Daxter"), what = "its sounds-like form")
        Device.screenshot("v1-screen-dictionary")

        await(By.text("Snippets"), what = "the navigation bar").click()
        await(By.textContains("my address"), what = "the trigger")
        await(By.textContains("Baker Street"), what = "the saved text")
        Device.screenshot("v1-screen-snippets")

        await(By.text("Settings"), what = "the navigation bar").click()
        Device.awaitScrolling(By.text("Styles")).click()
        await(By.textContains("Formal"), what = "the email tone")
        Device.screenshot("v1-screen-styles")
        Device.awaitScrolling(By.textContains("testhost"))
        Device.screenshot("v1-screen-styles-app-rule")
    }
}
