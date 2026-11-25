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

/*
 * What the Analyze space shows, the same for every platform. A platform fills
 * in what it can report (see `app.fediferry.channel.ChannelInsights`); the
 * screen never asks which platform a channel is on.
 */

/** `GET /insights?days=…`: every channel's numbers since [since], read live from the instances. */
@Serializable
data class InsightsPage(
    /** Epoch millis: posts and interactions before this are left out. */
    val since: Long,
    val channels: List<ChannelInsightsDto>,
    /** Channels whose instance could not be read just now. */
    val unreachable: List<String> = emptyList(),
)

@Serializable
data class ChannelInsightsDto(
    val channelId: String,
    val followers: Int,
    val following: Int,
    /** Every post the account ever made, as the instance counts them. */
    val posts: Int,
    /** The account's own posts since [InsightsPage.since], newest first. */
    val recent: List<PostStatsDto> = emptyList(),
    /** What others did since [InsightsPage.since], newest first; see [interactionsAccess]. */
    val interactions: List<InteractionDto> = emptyList(),
    val interactionsAccess: InteractionsAccess = InteractionsAccess.OK,
    /** One count per day, oldest first, as far back as FediFerry has kept them. */
    val followerHistory: List<FollowerCountDto> = emptyList(),
)

/** Whether [ChannelInsightsDto.interactions] could be read. */
@Serializable
enum class InteractionsAccess {
    OK,

    /** The account was connected before FediFerry asked to read interactions: connect it again. */
    RECONNECT,

    /** The platform does not report them. */
    UNSUPPORTED,
}

/** One of the account's posts with what it got. */
@Serializable
data class PostStatsDto(
    val id: String,
    val url: String? = null,
    val createdAt: Long,
    /** The text as it reads, markup gone. */
    val text: String,
    val previewUrl: String? = null,
    /** As the platform lists them, with their #. */
    val hashtags: List<String> = emptyList(),
    val favourites: Int = 0,
    val boosts: Int = 0,
    val replies: Int = 0,
    /** Null where the platform does not count quotes. */
    val quotes: Int? = null,
) {
    /** Everything others did with it. */
    val engagement: Int get() = favourites + boosts + replies + (quotes ?: 0)
}

/** Something another account did. */
@Serializable
data class InteractionDto(
    val kind: InteractionKind,
    val at: Long,
    /** Who, as `user@instance`. */
    val account: String,
    /** The account's post it was about; null for a follow or a mention that answers nothing of it. */
    val postId: String? = null,
)

@Serializable
enum class InteractionKind { FAVOURITE, BOOST, REPLY, QUOTE, MENTION, FOLLOW }

/** The follower count on one day, `2026-11-26`, as FediFerry recorded it. */
@Serializable
data class FollowerCountDto(val day: String, val followers: Int)
