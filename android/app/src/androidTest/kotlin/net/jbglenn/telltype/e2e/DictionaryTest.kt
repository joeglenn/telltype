package net.jbglenn.telltype.e2e

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.jbglenn.telltype.e2e.Device.TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Package V1, feature F2 (docs/PLAN.md section 4.5), done when "a term added to the dictionary is spelled correctly
 * in 10 of 10 dictations". The clip says "I pushed the fix to the Dagster pipeline"; the live speech model writes
 * "Daxter". Without the term the field gets "Daxter"; with the term (and its "sounds like" form) it gets "Dagster"
 * in each of ten dictations against the live server.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryTest {
    @get:Rule
    val failureShots = Scenario.failureShots

    @Before
    fun setUp() {
        Arrange.configuredApp()
        Device.enableService()
        FeatureDictation.realEngines()
        Arrange.clearDictionary()
        Arrange.settings { it.copy(cleanup = it.cleanup.copy(enabled = true)) }
    }

    @After
    fun tearDown() {
        Arrange.clearDictionary()
        FeatureDictation.defaultClip()
    }

    @Test
    fun aTermAddedToTheDictionaryIsSpelledCorrectlyInTenOfTenDictations() {
        val without = FeatureDictation.dictate("dictionary")
        assertFalse("without the term the server does not write it: '$without'", without.contains(TERM))

        Arrange.addTerm(TERM, listOf(HEARD))
        val results = (1..DICTATIONS).map { FeatureDictation.dictate("dictionary") }
        Device.screenshot("v1-f2-dictionary-term")
        val correct = results.count { it.contains(TERM) && !it.contains(HEARD) }
        Log.i(TAG, "v1 dictionary: $correct of $DICTATIONS spelled '$TERM'")
        assertEquals("dictations with '$TERM' spelled right: $results", DICTATIONS, correct)
        assertEquals("the term's uses were counted", DICTATIONS, Arrange.terms().single().uses)
    }

    private companion object {
        const val TERM = "Dagster"
        const val HEARD = "Daxter"
        const val DICTATIONS = 10
    }
}
