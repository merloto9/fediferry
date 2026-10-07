/*
 * FediFerry — share a meme screenshot straight to Mastodon.
 * Copyright (C) 2026 Jasper Ramthun
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.fediferry.work

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The arithmetic of scheduled times, in the phone's own time zone. The words
 * around them — "today at", "tomorrow" — come from `ScheduleWords`.
 */
object ScheduleFormat {

    /**
     * The earliest time the picker accepts. Mastodon wants five minutes; the
     * rest is room for the picture to upload first.
     */
    const val MIN_LEAD_MINUTES = 10L

    fun earliest(now: Long = System.currentTimeMillis()): Long = now + MIN_LEAD_MINUTES * 60_000

    /** The next full hour at least half an hour away: a sensible first offer. */
    fun suggested(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val start = Instant.ofEpochMilli(now).atZone(zone).plusMinutes(30)
        return start.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
    }

    /** Mastodon's ISO 8601 timestamp back to epoch millis; null if unreadable. */
    fun parse(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /** Whole days from [now] to [at] by the calendar: 0 today, 1 tomorrow. */
    fun dayOffset(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Long =
        ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate(),
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate(),
        )

    /** Whether [at] is far enough off for its date to need the year. */
    fun needsYear(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Boolean =
        dayOffset(at, now, zone) > 300

    /** [at] as a date in [pattern] — a locale's own pattern, see `ScheduleWords`. */
    fun dateText(at: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(pattern, locale).format(Instant.ofEpochMilli(at).atZone(zone))

    fun timeText(at: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(at).atZone(zone))

    /** The same instant as [date] at [hour]:[minute] in [zone]. */
    fun combine(date: LocalDate, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
}
