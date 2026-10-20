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
package app.fediferry.publishing

import app.fediferry.api.ScheduleDto

/** How schedules are listed, in Schedules and in "Next free slot of" alike. */
enum class ScheduleOrder {
    ALPHABETICAL,

    /** Soonest free slot first; a paused or full schedule last. */
    NEXT_FREE,

    /** As the person arranged them; one not arranged yet goes last. */
    MANUAL,
}

/** The next slot this schedule would give, or null when it is paused or has none free. */
fun ScheduleDto.nextSlot(): Long? = nextFree.firstOrNull()?.takeIf { active }

/** These schedules in [order]; [manual] holds schedule ids for [ScheduleOrder.MANUAL]. */
fun List<ScheduleDto>.ordered(order: ScheduleOrder, manual: List<String>): List<ScheduleDto> {
    val byName = compareBy<ScheduleDto> { it.name.lowercase() }
    return when (order) {
        ScheduleOrder.ALPHABETICAL -> sortedWith(byName)
        ScheduleOrder.NEXT_FREE -> sortedWith(compareBy<ScheduleDto>({ it.nextSlot() == null }, { it.nextSlot() ?: Long.MAX_VALUE }).then(byName))
        ScheduleOrder.MANUAL -> {
            val position = manual.withIndex().associate { (i, id) -> id to i }
            sortedWith(compareBy<ScheduleDto> { position[it.id] ?: Int.MAX_VALUE }.then(byName))
        }
    }
}
