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
package app.fediferry.api

import kotlinx.serialization.Serializable

/** A weekly slot: ISO days (1 Monday … 7 Sunday) at a wall-clock time, "HH:mm". */
@Serializable
data class SlotDto(val days: List<Int>, val time: String)

/**
 * An upload schedule: when posts for one channel go out. Posts planned "into
 * the next slot" take the soonest slot no other post has.
 */
@Serializable
data class ScheduleDto(
    val id: String,
    val channelId: String,
    val name: String,
    /** An IANA zone, "Europe/Berlin"; slot times are on its clock. */
    val timezone: String,
    val active: Boolean = true,
    val slots: List<SlotDto> = emptyList(),
    /** The next few slots no post has taken, for showing; epoch millis. */
    val nextFree: List<Long> = emptyList(),
)

@Serializable
data class ScheduleInput(
    val channelId: String? = null,
    val name: String? = null,
    val timezone: String? = null,
    val active: Boolean? = null,
    val slots: List<SlotDto>? = null,
)

object PublicationStates {
    const val QUEUED = "QUEUED"
    const val PUBLISHING = "PUBLISHING"
    const val PUBLISHED = "PUBLISHED"
    const val FAILED = "FAILED"
}

/** A post's way out: when it goes, how the tries went, and where it ended up. */
@Serializable
data class PublicationDto(
    val state: String,
    val channelId: String,
    val scheduleId: String? = null,
    /** The slot it took, when it was planned into one. */
    val slotAt: Long? = null,
    /** When it goes (or goes again, after a failed try). */
    val publishAfter: Long,
    val attempts: Int = 0,
    val remoteUrl: String? = null,
    /** Why the last try failed, as a code. */
    val failureCode: String? = null,
    val publishedAt: Long? = null,
)

object PlanModes {
    /** After [PlanRequest.delaySeconds] — the undo window — and nothing else. */
    const val NOW = "NOW"
    const val AT = "AT"
    /** The soonest free slot of the channel's schedules, or of [PlanRequest.scheduleId]. */
    const val NEXT_SLOT = "NEXT_SLOT"
}

/** `POST /posts/{id}/plan`: a ready post into the queue, or a planned one to a new time. */
@Serializable
data class PlanRequest(
    val mode: String,
    val at: Long? = null,
    val scheduleId: String? = null,
    val delaySeconds: Int = 0,
)
