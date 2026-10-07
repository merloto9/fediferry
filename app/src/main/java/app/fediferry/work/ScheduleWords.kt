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

import android.content.res.Resources
import android.text.format.DateFormat
import app.fediferry.R
import java.util.Locale

/** Scheduled times in words, in the app's language and the phone's date style. */
object ScheduleWords {

    /** "today at 14:00", "tomorrow at 09:30", "on Fri 9 Oct at 14:00". */
    fun whenText(res: Resources, at: Long, now: Long = System.currentTimeMillis()): String {
        val time = ScheduleFormat.timeText(at, locale = locale(res))
        return when (ScheduleFormat.dayOffset(at, now)) {
            0L -> res.getString(R.string.schedule_today_at, time)
            1L -> res.getString(R.string.schedule_tomorrow_at, time)
            else -> res.getString(R.string.schedule_on_date_at, date(res, at, now, SHORT), time)
        }
    }

    /** A day's heading: "Today", "Tomorrow", "Friday, 9 October". */
    fun dayHeading(res: Resources, at: Long, now: Long = System.currentTimeMillis()): String =
        when (ScheduleFormat.dayOffset(at, now)) {
            0L -> res.getString(R.string.schedule_today)
            1L -> res.getString(R.string.schedule_tomorrow)
            else -> date(res, at, now, LONG)
        }

    /** The full date and time: "Friday, 9 October 2026, 14:00". */
    fun fullText(res: Resources, at: Long): String =
        res.getString(
            R.string.schedule_date_time,
            ScheduleFormat.dateText(at, pattern(res, "EEEEdMMMMyyyy"), locale = locale(res)),
            ScheduleFormat.timeText(at, locale = locale(res)),
        )

    fun timeText(res: Resources, at: Long): String = ScheduleFormat.timeText(at, locale = locale(res))

    private fun date(res: Resources, at: Long, now: Long, skeleton: String): String {
        val withYear = if (ScheduleFormat.needsYear(at, now)) skeleton + "yyyy" else skeleton
        return ScheduleFormat.dateText(at, pattern(res, withYear), locale = locale(res))
    }

    /** The locale's own order and punctuation for these fields: "EEE d MMM", "EEE, d. MMM". */
    private fun pattern(res: Resources, skeleton: String): String =
        DateFormat.getBestDateTimePattern(locale(res), skeleton)

    private fun locale(res: Resources): Locale = res.configuration.locales[0]

    private const val SHORT = "EEEdMMM"
    private const val LONG = "EEEEdMMMM"
}
