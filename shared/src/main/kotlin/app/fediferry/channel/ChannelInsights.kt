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
package app.fediferry.channel

import app.fediferry.api.InteractionDto
import app.fediferry.api.InteractionsAccess
import app.fediferry.api.Platform
import app.fediferry.api.PostStatsDto
import app.fediferry.mastodon.MastodonInsights
import okhttp3.OkHttpClient

/** How to reach one channel's account. */
class ChannelAccess(val instance: String, val token: String, val remoteAccountId: String)

/** An account's totals, as its instance counts them. */
data class AccountCounts(val followers: Int, val following: Int, val posts: Int)

/** What a platform reports about one account; see [ChannelInsights]. */
class ChannelReading(
    val counts: AccountCounts,
    val recent: List<PostStatsDto>,
    val interactions: List<InteractionDto>,
    val interactionsAccess: InteractionsAccess,
)

/**
 * Reads an account's numbers from its platform. Everything platform-specific
 * about Analyze lives behind this: the phone and the server call it the same
 * way, and the screen only sees what it returns.
 *
 * [counts] must succeed or throw; [read] lets the parts beyond the counts
 * degrade to empty rather than fail the whole reading.
 */
interface ChannelInsights {

    /** Followers, following and posts only: what a daily snapshot needs. */
    suspend fun counts(access: ChannelAccess): AccountCounts

    /** Everything since [since] (epoch millis). */
    suspend fun read(access: ChannelAccess, since: Long): ChannelReading

    companion object {
        /** The reader for [platform], or null where FediFerry cannot read one yet. */
        fun forPlatform(platform: Platform?, http: OkHttpClient): ChannelInsights? = when (platform) {
            Platform.MASTODON, null -> MastodonInsights(http)
            else -> null
        }
    }
}
