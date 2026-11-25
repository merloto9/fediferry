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
package app.fediferry.mastodon

import app.fediferry.api.InteractionDto
import app.fediferry.api.InteractionKind
import app.fediferry.api.InteractionsAccess
import app.fediferry.api.PostStatsDto
import app.fediferry.channel.AccountCounts
import app.fediferry.channel.ChannelAccess
import app.fediferry.channel.ChannelInsights
import app.fediferry.channel.ChannelReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant

/**
 * Analyze's numbers from a Mastodon account: the counts from
 * `verify_credentials`, the account's own posts with their favourites,
 * boosts, replies and quotes, and its notifications for who did what when.
 *
 * Mastodon reports no reach or views, and no history of the follower count;
 * FediFerry keeps that itself (one count a day). Notifications need the
 * read:notifications scope, which accounts connected before 0.24 lack: they
 * come back as [InteractionsAccess.RECONNECT], never as a failure.
 */
class MastodonInsights(private val http: OkHttpClient) : ChannelInsights {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun counts(access: ChannelAccess): AccountCounts {
        val me = get<AccountWithCounts>(access, "api/v1/accounts/verify_credentials")
        return AccountCounts(me.followersCount, me.followingCount, me.statusesCount)
    }

    override suspend fun read(access: ChannelAccess, since: Long): ChannelReading {
        val counts = counts(access)
        // The rest only adds to the counts: a part that fails is left empty.
        val posts = runCatching { posts(access, since) }.getOrDefault(emptyList())
        val ownIds = posts.map { it.id }.toSet()
        var interactionsAccess = InteractionsAccess.OK
        val interactions = try {
            notifications(access, since).mapNotNull { interaction(it, ownIds) }
        } catch (e: MastodonException) {
            if (e.code == 403) interactionsAccess = InteractionsAccess.RECONNECT
            emptyList()
        }
        return ChannelReading(counts, posts, interactions, interactionsAccess)
    }

    /** The account's own posts since [since], newest first: no replies, no boosts. */
    private suspend fun posts(access: ChannelAccess, since: Long): List<PostStatsDto> {
        val all = mutableListOf<PostStatsDto>()
        var maxId: String? = null
        repeat(MAX_PAGES) {
            val page = get<List<StatusWithCounts>>(
                access, "api/v1/accounts/${access.remoteAccountId}/statuses",
                listOf("limit" to "40", "exclude_replies" to "true", "exclude_reblogs" to "true") + listOfNotNull(maxId?.let { "max_id" to it }),
            )
            val stats = page.mapNotNull(::stats)
            all += stats.filter { it.createdAt >= since }
            // Newest first: once the oldest on a page is too old, the rest are too.
            if (page.size < 40 || (stats.lastOrNull()?.createdAt ?: 0) < since) return all
            maxId = page.last().id
        }
        return all
    }

    private fun stats(status: StatusWithCounts): PostStatsDto? {
        val at = millis(status.createdAt) ?: return null
        val preview = status.media.firstOrNull()
        return PostStatsDto(
            id = status.id,
            url = status.url,
            createdAt = at,
            text = MastodonText.plain(status.content),
            previewUrl = preview?.previewUrl ?: preview?.url,
            hashtags = status.tags.map { "#${it.name}" },
            favourites = status.favouritesCount,
            boosts = status.reblogsCount,
            replies = status.repliesCount,
            quotes = status.quotesCount,
        )
    }

    /** Notifications of the kinds Analyze counts since [since], newest first. */
    private suspend fun notifications(access: ChannelAccess, since: Long): List<Notification> {
        val all = mutableListOf<Notification>()
        var maxId: String? = null
        repeat(MAX_PAGES) {
            val page = get<List<Notification>>(
                access, "api/v1/notifications",
                listOf("limit" to "80") + TYPES.map { "types[]" to it } + listOfNotNull(maxId?.let { "max_id" to it }),
            )
            val fresh = page.filter { (millis(it.createdAt) ?: 0) >= since }
            all += fresh
            if (page.size < 80 || fresh.size < page.size) return all
            maxId = page.last().id
        }
        return all
    }

    private fun interaction(n: Notification, ownIds: Set<String>): InteractionDto? {
        val at = millis(n.createdAt) ?: return null
        val (kind, postId) = when (n.type) {
            "favourite" -> InteractionKind.FAVOURITE to n.status?.id
            "reblog" -> InteractionKind.BOOST to n.status?.id
            "quote" -> InteractionKind.QUOTE to null
            "follow" -> InteractionKind.FOLLOW to null
            // A mention that answers one of the account's posts is a reply to it.
            "mention" -> n.status?.inReplyToId?.takeIf { it in ownIds }
                ?.let { InteractionKind.REPLY to it } ?: (InteractionKind.MENTION to null)
            else -> return null
        }
        return InteractionDto(kind, at, n.account.acct, postId)
    }

    private fun millis(iso: String): Long? = runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    private suspend inline fun <reified T> get(access: ChannelAccess, path: String, query: List<Pair<String, String>> = emptyList()): T =
        withContext(Dispatchers.IO) {
            val url = url(access.instance, path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${access.token}").get().build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw MastodonException("mastodon.unreachable", retryable = true, cause = e)
            }
            response.use {
                if (!it.isSuccessful) {
                    throw MastodonException(
                        when (it.code) {
                            401, 403 -> "mastodon.unauthorized"
                            404 -> "mastodon.not_found"
                            429 -> "mastodon.rate_limited"
                            else -> "mastodon.http_${it.code}"
                        },
                        it.code,
                        retryable = it.code == 429 || it.code >= 500,
                    )
                }
                try {
                    json.decodeFromString<T>(it.body.string())
                } catch (e: Exception) {
                    throw MastodonException("mastodon.unreadable", it.code, cause = e)
                }
            }
        }

    private fun url(instance: String, path: String): HttpUrl.Builder =
        HttpUrl.Builder().scheme("https").host(MastodonAccounts.normaliseInstance(instance)).addPathSegments(path)

    private companion object {
        /** At most this many pages of posts, and of notifications, per reading. */
        const val MAX_PAGES = 10
        val TYPES = listOf("favourite", "reblog", "mention", "follow", "quote")
    }
}
