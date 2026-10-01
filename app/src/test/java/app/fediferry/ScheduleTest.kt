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
package app.fediferry

import app.fediferry.mastodon.ScheduledStatus
import app.fediferry.work.ScheduleFormat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ScheduleTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val locale = Locale.UK

    /** Wednesday 1 October 2026, 10:20 in Berlin. */
    private val now = ZonedDateTime.of(2026, 10, 1, 10, 20, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10, year: Int = 2026) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun saysWhenInWordsAPersonUses() {
        assertEquals("today at 14:00", ScheduleFormat.whenText(at(1, 14), now, zone, locale))
        assertEquals("tomorrow at 09:30", ScheduleFormat.whenText(at(2, 9, 30), now, zone, locale))
        assertEquals("on Fri 9 Oct at 18:00", ScheduleFormat.whenText(at(9, 18), now, zone, locale))
        assertEquals("on Mon 4 Oct 2027 at 08:00", ScheduleFormat.whenText(at(4, 8, year = 2027), now, zone, locale))
    }

    @Test
    fun headsEachDayOfTheQueue() {
        assertEquals("Today", ScheduleFormat.dayHeading(at(1, 23, 59), now, zone, locale))
        assertEquals("Tomorrow", ScheduleFormat.dayHeading(at(2, 0, 0), now, zone, locale))
        assertEquals("Saturday, 3 October", ScheduleFormat.dayHeading(at(3, 12), now, zone, locale))
    }

    @Test
    fun offersTheNextFullHourAtLeastHalfAnHourAway() {
        assertEquals(at(1, 11), ScheduleFormat.suggested(now, zone))
        // 10:40 plus half an hour is 11:10, so the offer is noon.
        assertEquals(at(1, 12), ScheduleFormat.suggested(at(1, 10, 40), zone))
    }

    @Test
    fun combinesTheDayAndTimeInThePhonesZone() {
        assertEquals(at(9, 18, 15), ScheduleFormat.combine(LocalDate.of(2026, 10, 9), 18, 15, zone))
    }

    @Test
    fun readsMastodonsTimestamps() {
        // 12:00 UTC is 14:00 in Berlin's summer time.
        assertEquals(at(9, 14), ScheduleFormat.parse("2026-10-09T12:00:00.000Z"))
        assertNull(ScheduleFormat.parse("soon"))
    }

    @Test
    fun readsAScheduledPostAsMastodonSendsIt() {
        // The shape from docs.joinmastodon.org/entities/ScheduledStatus.
        val body = """
            {"id":"3221","scheduled_at":"2026-10-09T12:00:00.000Z",
             "params":{"poll":null,"text":"Look at this","media_ids":["1"],"sensitive":null,
                       "visibility":"public","idempotency":null,"scheduled_at":null,
                       "spoiler_text":null,"application_id":596551,"in_reply_to_id":null},
             "media_attachments":[{"id":"1","type":"image","url":"https://files/1.png",
                                   "preview_url":"https://files/small/1.png","description":"A cat"}]}
        """.trimIndent()
        val status = Json { ignoreUnknownKeys = true }.decodeFromString<ScheduledStatus>(body)
        assertEquals("3221", status.id)
        assertEquals("Look at this", status.params.text)
        assertEquals("https://files/small/1.png", status.media.single().previewUrl)
        assertEquals("A cat", status.media.single().description)
        assertNull(status.params.spoilerText)
    }
}
