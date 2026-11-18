package net.jbglenn.telltype.ui.screens.common

import android.icu.text.DateFormat
import android.icu.util.TimeZone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import net.jbglenn.telltype.ui.screens.R

/**
 * Locale-aware formatting of times and durations. Pure functions; the composables below pick the locale.
 *
 * Every date and time is formatted by Android's ICU from a skeleton, pattern and data together (B11). An ICU pattern
 * handed to `java.time` is read with other locale data (digits, month names, day periods differ in about one locale
 * in six), and History and Home, the only screens formatted that way, showed 12:00 a.m. for every dictation on an
 * Android 17 phone. The hour cycle follows the locale's `hc` keyword, which [currentLocale] sets from the phone's
 * 12/24-hour setting.
 */
internal object Formats {
    private const val TIME_SKELETON = "jmm"
    private const val DAY_TIME_SKELETON = "MMMdjmm"
    private const val DATE_TIME_SKELETON = "yMMMdjmm"

    /** "09:12" or "9:12 AM". */
    fun time(instant: Instant, zone: ZoneId, locale: Locale): String = format(TIME_SKELETON, instant, zone, locale)

    /** "Nov 17, 9:12 AM" or "17 Nov, 09:12": day and time without the year, in the locale's own order. */
    fun dayAndTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        format(DAY_TIME_SKELETON, instant, zone, locale)

    /** "17 Nov 2026, 09:12". */
    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        format(DATE_TIME_SKELETON, instant, zone, locale)

    private fun format(skeleton: String, instant: Instant, zone: ZoneId, locale: Locale): String {
        val format = DateFormat.getInstanceForSkeleton(skeleton, locale)
        // java.util.TimeZone names offsets and "Z" the way ICU reads them ("GMT+05:30", "UTC").
        format.timeZone = TimeZone.getTimeZone(java.util.TimeZone.getTimeZone(zone).id)
        return format.format(Date(instant.toEpochMilliseconds()))
    }

    /** [locale] with the hour cycle of the phone's 12/24-hour setting (`hc` keyword); unchanged when unknown. */
    fun withHourCycle(locale: Locale, use24Hour: Boolean?): Locale = if (use24Hour == null) {
        locale
    } else {
        Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("hc", if (use24Hour) "h23" else "h12").build()
    }
}

/** The current locale of the configuration, with the phone's 12/24-hour setting as its hour cycle. */
@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = Formats.withHourCycle(
    LocalConfiguration.current.locales[0],
    android.text.format.DateFormat.is24HourFormat(LocalContext.current),
)

/** "340 ms", "2.4 s" or "1 min 05 s", with the locale's digits and separators. */
@Composable
@ReadOnlyComposable
internal fun durationText(duration: Duration): String = when {
    duration < OneSecond -> stringResource(R.string.duration_millis, duration.inWholeMilliseconds)

    duration < OneMinute -> stringResource(
        R.string.duration_seconds,
        duration.inWholeMilliseconds / FormatDefaults.MILLIS_PER_SECOND,
    )

    else -> stringResource(
        R.string.duration_minutes_seconds,
        duration.inWholeMinutes,
        duration.inWholeSeconds % FormatDefaults.SECONDS_PER_MINUTE,
    )
}

private val OneSecond = 1.seconds
private val OneMinute = 60.seconds

/** Fixed values used in this file. */
private object FormatDefaults {
    const val MILLIS_PER_SECOND = 1000.0
    const val SECONDS_PER_MINUTE = 60
}
