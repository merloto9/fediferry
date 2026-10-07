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

import app.fediferry.schedule.Slot
import app.fediferry.schedule.SlotPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class SlotPlannerTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val everyDay = DayOfWeek.entries.toSet()
    private fun at(text: String): Instant = ZonedDateTime.parse(text).toInstant()

    @Test
    fun `the next slot is the soonest after now`() {
        val slots = listOf(Slot(everyDay, LocalTime.of(18, 0)), Slot(everyDay, LocalTime.of(9, 0)))
        val next = SlotPlanner.nextFree(berlin, slots, at("2026-10-08T12:00+02:00[Europe/Berlin]"), emptyList())
        assertEquals(at("2026-10-08T18:00+02:00[Europe/Berlin]"), next)
    }

    @Test
    fun `a taken slot is skipped`() {
        val slots = listOf(Slot(everyDay, LocalTime.of(18, 0)))
        val taken = listOf(at("2026-10-08T18:00+02:00[Europe/Berlin]"))
        assertEquals(at("2026-10-09T18:00+02:00[Europe/Berlin]"), SlotPlanner.nextFree(berlin, slots, at("2026-10-08T12:00+02:00[Europe/Berlin]"), taken))
    }

    @Test
    fun `slots keep to their days`() {
        // Thursday the 8th; the next Monday is the 12th.
        val slots = listOf(Slot(setOf(DayOfWeek.MONDAY), LocalTime.of(7, 30)))
        assertEquals(at("2026-10-12T07:30+02:00[Europe/Berlin]"), SlotPlanner.nextFree(berlin, slots, at("2026-10-08T12:00+02:00[Europe/Berlin]"), emptyList()))
    }

    @Test
    fun `a slot keeps its wall-clock time across the autumn clock change`() {
        // Clocks go back on 25 October 2026: 18:00 is +02:00 before, +01:00 after.
        val slots = listOf(Slot(everyDay, LocalTime.of(18, 0)))
        val times = SlotPlanner.upcoming(berlin, slots, at("2026-10-24T12:00+02:00[Europe/Berlin]")).take(2).toList()
        assertEquals(listOf(at("2026-10-24T18:00+02:00[Europe/Berlin]"), at("2026-10-25T18:00+01:00[Europe/Berlin]")), times)
    }

    @Test
    fun `a slot in the skipped spring hour moves an hour on`() {
        // 29 March 2026: 02:00 jumps to 03:00, so 02:30 does not exist.
        val slots = listOf(Slot(everyDay, LocalTime.of(2, 30)))
        val next = SlotPlanner.nextFree(berlin, slots, at("2026-03-29T00:00+01:00[Europe/Berlin]"), emptyList())
        assertEquals(at("2026-03-29T03:30+02:00[Europe/Berlin]"), next)
    }

    @Test
    fun `a slot in the repeated autumn hour is taken once`() {
        val slots = listOf(Slot(everyDay, LocalTime.of(2, 30)))
        val times = SlotPlanner.upcoming(berlin, slots, at("2026-10-25T00:00+02:00[Europe/Berlin]")).take(2).toList()
        assertEquals(listOf(at("2026-10-25T02:30+02:00[Europe/Berlin]"), at("2026-10-26T02:30+01:00[Europe/Berlin]")), times)
    }

    @Test
    fun `a schedule without days offers nothing`() =
        assertNull(SlotPlanner.nextFree(berlin, listOf(Slot(emptySet(), LocalTime.NOON)), Instant.EPOCH, emptyList()))
}
