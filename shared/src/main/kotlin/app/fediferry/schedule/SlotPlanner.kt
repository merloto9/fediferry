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
package app.fediferry.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A weekly time to post at: on these [days], at [time] on the schedule's clock. */
data class Slot(val days: Set<DayOfWeek>, val time: LocalTime)

/**
 * Finds the times an upload schedule offers. Slots are wall-clock times in the
 * schedule's own zone, so "every day at 18:00" stays 18:00 across the clock
 * change. A slot that falls in the hour skipped in spring moves forward by
 * that hour; one in the hour repeated in autumn is taken the first time round.
 */
object SlotPlanner {
    /** How far ahead to look before giving up: a full year of slots. */
    private const val HORIZON_DAYS = 366L

    /** Slot times strictly after [from], soonest first. */
    fun upcoming(zone: ZoneId, slots: List<Slot>, from: Instant): Sequence<Instant> {
        if (slots.none { it.days.isNotEmpty() }) return emptySequence()
        val start = from.atZone(zone).toLocalDate()
        return generateSequence(start) { it.plusDays(1) }
            .take(HORIZON_DAYS.toInt() + 1)
            .flatMap { date -> onDate(zone, slots, date) }
            .filter { it.isAfter(from) }
    }

    /** The first slot after [from] that no post has taken yet; null when the schedule offers none. */
    fun nextFree(zone: ZoneId, slots: List<Slot>, from: Instant, taken: Collection<Instant>): Instant? {
        val busy = taken.toHashSet()
        return upcoming(zone, slots, from).firstOrNull { it !in busy }
    }

    private fun onDate(zone: ZoneId, slots: List<Slot>, date: LocalDate): Sequence<Instant> =
        slots.asSequence()
            .filter { date.dayOfWeek in it.days }
            .map { ZonedDateTime.of(date, it.time, zone).toInstant() }
            .distinct()
            .sorted()
}
