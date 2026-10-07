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
