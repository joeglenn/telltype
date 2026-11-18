package net.jbglenn.telltype.ui.screens.common

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import net.jbglenn.telltype.ui.screens.R

/** Locale-aware formatting of times and durations. Pure functions; the composables below pick the locale. */
internal object Formats {
    private const val DAY_TIME_SKELETON = "MMMdjmm"

    /** "09:12" or "9:12 AM". */
    fun time(instant: Instant, zone: ZoneId, locale: Locale): String = DateTimeFormatter.ofLocalizedTime(
        FormatStyle.SHORT,
    ).withLocale(locale).format(instant.toJavaInstant().atZone(zone))

    /** "Nov 17, 9:12 AM" or "17 Nov, 09:12": day and time without the year, in the locale's own order. */
    fun dayAndTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, DAY_TIME_SKELETON), locale)
            .format(instant.toJavaInstant().atZone(zone))

    /** "17 Nov 2026, 09:12". */
    fun dateTime(instant: Instant, zone: ZoneId, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(instant.toJavaInstant().atZone(zone))
}

/** The current locale of the configuration. */
@Composable
@ReadOnlyComposable
internal fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

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
