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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppRegistration(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    val scope: String = "",
)

@Serializable
data class CredentialAccount(
    val id: String,
    val acct: String,
    @SerialName("display_name") val displayName: String = "",
    val avatar: String? = null,
)

@Serializable
data class MediaAttachment(
    val id: String,
    val url: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val description: String? = null,
    /** image, gifv, video, audio or unknown. */
    val type: String? = null,
)

@Serializable
data class PostedStatus(
    val id: String,
    val url: String? = null,
)

/** A post on an account's timeline, as `GET /api/v1/accounts/:id/statuses` lists it. */
@Serializable
data class AccountStatus(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val url: String? = null,
    /** HTML; see [MastodonText.plain]. */
    val content: String = "",
    @SerialName("spoiler_text") val spoilerText: String = "",
    @SerialName("media_attachments") val media: List<MediaAttachment> = emptyList(),
)

/** A post Mastodon holds to publish later. Only the server keeps these. */
@Serializable
data class ScheduledStatus(
    val id: String,
    @SerialName("scheduled_at") val scheduledAt: String,
    val params: ScheduledParams = ScheduledParams(),
    @SerialName("media_attachments") val media: List<MediaAttachment> = emptyList(),
)

@Serializable
data class ScheduledParams(
    val text: String = "",
    @SerialName("spoiler_text") val spoilerText: String? = null,
    val visibility: String? = null,
)

/** The body Mastodon sends with an error: a reason, never the post. */
@Serializable
data class ErrorBody(val error: String? = null)

// --- for Analyze (MastodonInsights) -----------------------------------------------

@Serializable
data class AccountWithCounts(
    val id: String,
    @SerialName("followers_count") val followersCount: Int = 0,
    @SerialName("following_count") val followingCount: Int = 0,
    @SerialName("statuses_count") val statusesCount: Int = 0,
)

@Serializable
data class StatusTag(val name: String)

/** One of the account's posts with its counts. `quotes_count` arrived in Mastodon 4.5. */
@Serializable
data class StatusWithCounts(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val url: String? = null,
    val content: String = "",
    @SerialName("media_attachments") val media: List<MediaAttachment> = emptyList(),
    val tags: List<StatusTag> = emptyList(),
    @SerialName("favourites_count") val favouritesCount: Int = 0,
    @SerialName("reblogs_count") val reblogsCount: Int = 0,
    @SerialName("replies_count") val repliesCount: Int = 0,
    @SerialName("quotes_count") val quotesCount: Int? = null,
)

@Serializable
data class NotificationAccount(val acct: String = "")

@Serializable
data class NotificationStatus(
    val id: String,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
)

/** `GET /api/v1/notifications`; needs the read:notifications scope. */
@Serializable
data class Notification(
    val id: String,
    val type: String,
    @SerialName("created_at") val createdAt: String,
    val account: NotificationAccount = NotificationAccount(),
    val status: NotificationStatus? = null,
)
