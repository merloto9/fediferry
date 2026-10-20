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

import app.fediferry.api.ScheduleDto
import app.fediferry.publishing.ScheduleOrder
import app.fediferry.publishing.ordered
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleOrderTest {

    private fun schedule(id: String, name: String, next: Long? = null, active: Boolean = true) =
        ScheduleDto(id, "c", name, "UTC", active, nextFree = listOfNotNull(next))

    private val evenings = schedule("e", "evenings", next = 300)
    private val mornings = schedule("m", "Mornings", next = 100)
    private val paused = schedule("p", "Afternoons", next = 50, active = false)
    private val full = schedule("f", "Breakfast")
    private val all = listOf(evenings, mornings, paused, full)

    @Test
    fun alphabeticalIgnoresCase() {
        assertEquals(listOf("p", "f", "e", "m"), all.ordered(ScheduleOrder.ALPHABETICAL, emptyList()).map { it.id })
    }

    @Test
    fun nextFreePutsPausedAndFullLast() {
        assertEquals(listOf("m", "e", "p", "f"), all.ordered(ScheduleOrder.NEXT_FREE, emptyList()).map { it.id })
    }

    @Test
    fun manualKeepsTheArrangedOrderAndAddsNewOnesLast() {
        val arranged = listOf("m", "gone", "e")
        assertEquals(listOf("m", "e", "p", "f"), all.ordered(ScheduleOrder.MANUAL, arranged).map { it.id })
    }
}
