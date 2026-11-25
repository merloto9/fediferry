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
package app.fediferry.server

import app.fediferry.api.ChannelInsightsDto
import app.fediferry.api.FollowerCountDto
import app.fediferry.api.InsightsPage
import app.fediferry.api.Platform
import app.fediferry.channel.ChannelAccess
import app.fediferry.channel.ChannelInsights
import app.fediferry.server.db.Channel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Analyze on the server: every channel read live through its platform's
 * [ChannelInsights], with the follower counts this server has kept. Nothing
 * here knows a platform; a channel whose platform has no reader is left out.
 */
class Insights(
    private val storage: Storage,
    private val channels: Channels,
    private val readerFor: (Platform?) -> ChannelInsights?,
    private val clock: () -> Long,
) {
    private val db get() = storage.db
    private val log = LoggerFactory.getLogger("insights")

    /** The last [days] days of every connected channel; a channel that cannot be read is named, never failing the rest. */
    suspend fun read(projectId: String, days: Int): InsightsPage = coroutineScope {
        val now = clock()
        val since = now - days.coerceIn(1, MAX_DAYS) * DAY_MS
        val firstDay = day(since)
        val readings = connected(projectId).map { channel ->
            async {
                val reader = readerFor(Platform.fromName(channel.platform)) ?: return@async channel.id to null
                val access = access(projectId, channel) ?: return@async channel.id to null
                channel.id to runCatching { reader.read(access, since) }
                    .onFailure { log.info("Reading a channel for Analyze failed: {}", it.message) }
                    .getOrNull()
            }
        }.awaitAll()
        val result = readings.mapNotNull { (channelId, reading) ->
            reading ?: return@mapNotNull null
            // Reading the account is as good a moment as any to note today's count.
            record(projectId, channelId, reading.counts.followers, now)
            ChannelInsightsDto(
                channelId = channelId,
                followers = reading.counts.followers,
                following = reading.counts.following,
                posts = reading.counts.posts,
                recent = reading.recent,
                interactions = reading.interactions,
                interactionsAccess = reading.interactionsAccess,
                followerHistory = history(projectId, channelId, firstDay),
            )
        }
        InsightsPage(since, result, readings.filter { it.second == null }.map { it.first })
    }

    /** Notes today's follower count of every channel that has none yet. Called by the hourly sweep. */
    suspend fun recordToday(projectId: String) {
        val now = clock()
        for (channel in connected(projectId)) {
            if (db.followerCountQueries.has(projectId, channel.id, day(now)).executeAsOne() > 0) continue
            val reader = readerFor(Platform.fromName(channel.platform)) ?: continue
            val access = access(projectId, channel) ?: continue
            runCatching { reader.counts(access) }
                .onSuccess { record(projectId, channel.id, it.followers, now) }
                .onFailure { log.info("Noting a channel's follower count failed: {}", it.message) }
        }
    }

    private fun connected(projectId: String): List<Channel> =
        db.channelQueries.all(projectId).executeAsList().filter { it.acct.isNotEmpty() && it.remote_account_id.isNotEmpty() }

    private fun access(projectId: String, channel: Channel): ChannelAccess? =
        channels.token(projectId, channel.id)?.let { ChannelAccess(channel.instance, it, channel.remote_account_id) }

    private fun record(projectId: String, channelId: String, followers: Int, now: Long) =
        db.followerCountQueries.record(projectId, channelId, day(now), followers.toLong(), now)

    private fun history(projectId: String, channelId: String, from: String): List<FollowerCountDto> =
        db.followerCountQueries.since(projectId, channelId, from).executeAsList().map { FollowerCountDto(it.day, it.followers.toInt()) }

    companion object {
        const val MAX_DAYS = 90
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** Days are UTC dates, `2026-11-26`, the same for every phone of the project. */
        fun day(millis: Long): String = LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC).toString()
    }
}

fun Route.insightsRoutes(services: Services) {
    get("/insights") {
        val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
        call.respond(services.insights.read(call.projectId(), days))
    }
}
