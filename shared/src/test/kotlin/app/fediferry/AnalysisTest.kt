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

import app.fediferry.api.ChannelInsightsDto
import app.fediferry.api.FollowerCountDto
import app.fediferry.api.InteractionDto
import app.fediferry.api.InteractionKind
import app.fediferry.api.InteractionsAccess
import app.fediferry.api.PostStatsDto
import app.fediferry.insights.Analysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class AnalysisTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun post(id: String, at: String, favs: Int, boosts: Int = 0, replies: Int = 0, tags: List<String> = emptyList()) =
        PostStatsDto(id = id, createdAt = Instant.parse(at).toEpochMilli(), text = id, hashtags = tags, favourites = favs, boosts = boosts, replies = replies)

    // Monday 23 November 2026, 17:00 and 18:00 in Berlin; Tuesday 24, 09:00.
    private val posts = listOf(
        post("a", "2026-11-23T16:00:00Z", favs = 10, boosts = 2, tags = listOf("#Meme", "#cats")),
        post("b", "2026-11-23T17:00:00Z", favs = 4, tags = listOf("#meme")),
        post("c", "2026-11-24T08:00:00Z", favs = 1, tags = listOf("#cats", "#rare")),
    )

    @Test
    fun `totals add up and divide by the posts`() {
        val t = Analysis.totals(posts)
        assertEquals(3, t.posts)
        assertEquals(17, t.engagement)
        assertEquals(17.0 / 3, t.perPost, 1e-9)
        assertEquals(0.0, Analysis.totals(emptyList()).perPost, 0.0)
    }

    @Test
    fun `top posts by engagement`() {
        assertEquals(listOf("a", "b"), Analysis.topPosts(posts, 2).map { it.id })
    }

    @Test
    fun `best times are weekday and hour in the viewer's zone`() {
        val slots = Analysis.bestTimes(posts, berlin)
        assertEquals(listOf(Triple(1, 17, 12.0), Triple(1, 18, 4.0), Triple(2, 9, 1.0)), slots.map { Triple(it.dayOfWeek, it.hour, it.perPost) })
    }

    @Test
    fun `hashtags rank by what their posts got, case ignored, rare ones left out`() {
        val tags = Analysis.hashtags(posts)
        assertEquals(listOf("#Meme" to 8.0, "#cats" to 6.5), tags.map { it.tag to it.perPost })
        assertEquals(2, tags.first().posts)
    }

    @Test
    fun `interactions per day and the most active accounts`() {
        val at = { s: String -> Instant.parse(s).toEpochMilli() }
        val interactions = listOf(
            InteractionDto(InteractionKind.FAVOURITE, at("2026-11-23T20:00:00Z"), "ann@a"),
            InteractionDto(InteractionKind.BOOST, at("2026-11-23T21:00:00Z"), "ann@a"),
            // 23:30 UTC is already the 24th in Berlin.
            InteractionDto(InteractionKind.FAVOURITE, at("2026-11-23T23:30:00Z"), "bob@b"),
            InteractionDto(InteractionKind.FOLLOW, at("2026-11-24T10:00:00Z"), "cy@c"),
        )
        val days = Analysis.perDay(interactions, berlin)
        assertEquals(listOf(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24)), days.map { it.first })
        assertEquals(mapOf(InteractionKind.FAVOURITE to 1, InteractionKind.FOLLOW to 1), days[1].second)
        assertEquals(listOf("ann@a" to 2, "bob@b" to 1), Analysis.topAccounts(interactions).map { it.account to it.interactions })
    }

    @Test
    fun `follower change from the first recorded day in the period`() {
        val history = listOf(FollowerCountDto("2026-11-01", 100), FollowerCountDto("2026-11-20", 110))
        assertEquals(15, Analysis.followerChange(history, LocalDate.of(2026, 11, 10), current = 125))
        assertNull(Analysis.followerChange(history, LocalDate.of(2026, 11, 25), current = 125))
    }

    @Test
    fun `channels combine, a day of history only where every channel has it, a reconnect stays visible`() {
        val one = ChannelInsightsDto("1", 100, 10, 50, history = listOf(FollowerCountDto("2026-11-24", 99), FollowerCountDto("2026-11-25", 100)))
        val two = ChannelInsightsDto("2", 20, 5, 7, interactionsAccess = InteractionsAccess.RECONNECT, history = listOf(FollowerCountDto("2026-11-25", 20)))
        val both = Analysis.combine(listOf(one, two))
        assertEquals(120, both.followers)
        assertEquals(listOf(FollowerCountDto("2026-11-25", 120)), both.followerHistory)
        assertEquals(InteractionsAccess.RECONNECT, both.interactionsAccess)
    }

    private fun ChannelInsightsDto(
        id: String,
        followers: Int,
        following: Int,
        posts: Int,
        interactionsAccess: InteractionsAccess = InteractionsAccess.OK,
        history: List<FollowerCountDto> = emptyList(),
    ) = ChannelInsightsDto(channelId = id, followers = followers, following = following, posts = posts, interactionsAccess = interactionsAccess, followerHistory = history)
}
