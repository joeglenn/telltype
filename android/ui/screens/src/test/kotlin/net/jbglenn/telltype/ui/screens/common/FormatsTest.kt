package net.jbglenn.telltype.ui.screens.common

import android.app.Application
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** B11: dictation times in several zones, on 12- and 24-hour clocks, never collapse to midnight. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FormatsTest {
    /** 18 Nov 2026, 15:42 UTC. */
    private val instant = Instant.parse("2026-11-18T15:42:00Z")

    private data class Case(val zone: String, val twelve: String, val twentyFour: String)

    private val cases = listOf(
        Case("America/Toronto", "10:42", "10:42"),
        Case("America/Vancouver", "7:42", "07:42"),
        Case("America/St_Johns", "12:12", "12:12"),
        Case("Asia/Kolkata", "9:12", "21:12"),
        Case("UTC", "3:42", "15:42"),
        Case("Z", "3:42", "15:42"),
    )

    private val canada = Locale.forLanguageTag("en-CA")

    @Test
    fun historyAndHomeShowTheLocalTimeOnATwelveHourClock() {
        for (case in cases) {
            val zone = ZoneId.of(case.zone)
            val twelve = Formats.withHourCycle(canada, use24Hour = false)
            val day = Formats.dayAndTime(instant, zone, twelve)
            assertTrue("${case.zone}: $day", day.contains(case.twelve))
            assertTrue("${case.zone}: $day", day.contains("a.m.") || day.contains("p.m."))
            assertTrue("${case.zone}: $day", day.startsWith("Nov"))
            assertTrue(Formats.dateTime(instant, zone, twelve).contains(case.twelve))
            assertTrue(Formats.time(instant, zone, twelve).contains(case.twelve))
            assertFalse("${case.zone}: $day", day.contains("12:00"))
        }
    }

    @Test
    fun theTwentyFourHourSettingIsFollowed() {
        for (case in cases) {
            val zone = ZoneId.of(case.zone)
            val clock = Formats.withHourCycle(canada, use24Hour = true)
            val day = Formats.dayAndTime(instant, zone, clock)
            assertTrue("${case.zone}: $day", day.contains(case.twentyFour))
            assertFalse("${case.zone}: $day", day.contains("a.m.") || day.contains("p.m."))
            assertEquals(Formats.time(instant, zone, clock), case.twentyFour)
        }
        val gb = Formats.dayAndTime(instant, ZoneId.of("Europe/London"), Locale.UK)
        assertEquals("18 Nov, 15:42", gb)
    }

    @Test
    fun theLocaleSpeaksItsOwnDigitsAndWords() {
        // The ICU pattern used to be filled in by java.time, with its own data: Latin digits here (fails before B11).
        val egypt = Formats.dayAndTime(instant, ZoneId.of("Africa/Cairo"), Locale.forLanguageTag("ar-EG"))
        assertTrue(egypt, egypt.contains("\u0665:\u0664\u0662"))
        assertFalse(egypt, egypt.contains("5:42"))
    }
}
