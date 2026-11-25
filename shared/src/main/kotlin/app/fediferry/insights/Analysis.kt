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
package app.fediferry.insights

import app.fediferry.api.ChannelInsightsDto
import app.fediferry.api.FollowerCountDto
import app.fediferry.api.InteractionDto
import app.fediferry.api.InteractionKind
import app.fediferry.api.InteractionsAccess
import app.fediferry.api.PostStatsDto
import app.fediferry.data.model.Hashtags
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What Analyze makes of the numbers, for one channel or several taken
 * together. Platform-neutral and pure: it reads only the `api` types, so it
 * is the same for every platform and every backend.
 */
object Analysis {

    data class Totals(val posts: Int, val favourites: Int, val boosts: Int, val replies: Int, val quotes: Int) {
        val engagement: Int get() = favourites + boosts + replies + quotes

        /** Per post, or 0 without posts. */
        val perPost: Double get() = if (posts == 0) 0.0 else engagement.toDouble() / posts
    }

    fun totals(posts: List<PostStatsDto>) = Totals(
        posts = posts.size,
        favourites = posts.sumOf { it.favourites },
        boosts = posts.sumOf { it.boosts },
        replies = posts.sumOf { it.replies },
        quotes = posts.sumOf { it.quotes ?: 0 },
    )

    /** Most engagement first; equal ones newest first. */
    fun topPosts(posts: List<PostStatsDto>, n: Int = 5): List<PostStatsDto> =
        posts.sortedWith(compareByDescending<PostStatsDto> { it.engagement }.thenByDescending { it.createdAt }).take(n)

    /** One cell of the best-time grid: posts made in that hour of that weekday, and what they got on average. */
    data class Slot(val dayOfWeek: Int, val hour: Int, val posts: Int, val perPost: Double)

    /**
     * The week as a grid, Monday 1 to Sunday 7 and hour 0 to 23 in [zone],
     * with what posts made then got on average. Only cells with posts.
     */
    fun bestTimes(posts: List<PostStatsDto>, zone: ZoneId): List<Slot> =
        posts.groupBy {
            val t = Instant.ofEpochMilli(it.createdAt).atZone(zone)
            t.dayOfWeek.value to t.hour
        }.map { (key, group) -> Slot(key.first, key.second, group.size, group.sumOf { it.engagement }.toDouble() / group.size) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.hour }))

    data class TagStats(val tag: String, val posts: Int, val perPost: Double)

    /**
     * How posts with each hashtag did, best first. A tag on fewer than
     * [minPosts] posts says too little to rank, so it is left out.
     */
    fun hashtags(posts: List<PostStatsDto>, minPosts: Int = 2): List<TagStats> =
        posts.flatMap { p -> p.hashtags.distinctBy(Hashtags::key).map { Hashtags.key(it) to p } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size >= minPosts }
            .map { (key, group) ->
                // Spelled as it was written most often.
                val spelling = group.flatMap { it.hashtags }.filter { Hashtags.key(it) == key }
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                TagStats(spelling, group.size, group.sumOf { it.engagement }.toDouble() / group.size)
            }
            .sortedWith(compareByDescending<TagStats> { it.perPost }.thenByDescending { it.posts }.thenBy { Hashtags.key(it.tag) })

    /** Interactions per day in [zone] and kind, oldest day first; days without any are left out. */
    fun perDay(interactions: List<InteractionDto>, zone: ZoneId): List<Pair<LocalDate, Map<InteractionKind, Int>>> =
        interactions.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
            .mapValues { (_, list) -> list.groupingBy { it.kind }.eachCount() }
            .toSortedMap().toList()

    data class Fan(val account: String, val interactions: Int)

    /** The accounts that interacted most, follows left out. */
    fun topAccounts(interactions: List<InteractionDto>, n: Int = 5): List<Fan> =
        interactions.filter { it.kind != InteractionKind.FOLLOW && it.account.isNotBlank() }
            .groupingBy { it.account }.eachCount()
            .map { Fan(it.key, it.value) }
            .sortedWith(compareByDescending<Fan> { it.interactions }.thenBy { it.account })
            .take(n)

    /**
     * How the follower count changed from the first recorded day on or after
     * [from] to [current]; null when nothing was recorded that far back.
     */
    fun followerChange(history: List<FollowerCountDto>, from: LocalDate, current: Int): Int? =
        history.firstOrNull { !LocalDate.parse(it.day).isBefore(from) }?.let { current - it.followers }

    /** Several channels as one: every list joined, counts added up. */
    fun combine(channels: List<ChannelInsightsDto>): ChannelInsightsDto = ChannelInsightsDto(
        channelId = "",
        followers = channels.sumOf { it.followers },
        following = channels.sumOf { it.following },
        posts = channels.sumOf { it.posts },
        recent = channels.flatMap { it.recent }.sortedByDescending { it.createdAt },
        interactions = channels.flatMap { it.interactions }.sortedByDescending { it.at },
        // One channel to reconnect is worth saying, even when the others are fine.
        interactionsAccess = when {
            channels.any { it.interactionsAccess == InteractionsAccess.RECONNECT } -> InteractionsAccess.RECONNECT
            channels.isNotEmpty() && channels.all { it.interactionsAccess == InteractionsAccess.UNSUPPORTED } -> InteractionsAccess.UNSUPPORTED
            else -> InteractionsAccess.OK
        },
        followerHistory = channels.flatMap { it.followerHistory }
            .groupBy { it.day }
            // A day counts only once every channel has a number for it.
            .filterValues { it.size == channels.size }
            .map { (day, list) -> FollowerCountDto(day, list.sumOf { it.followers }) }
            .sortedBy { it.day },
    )
}
