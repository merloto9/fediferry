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

object ChannelTypes {
    const val MASTODON = "MASTODON"
}

/**
 * What a channel's destination takes, read from the server it lives on. A
 * post is checked against these before it can be ready, and the editor shows
 * them while writing. Unknown values fall back to Mastodon's defaults.
 */
@Serializable
data class ChannelCapabilities(
    val maxCharacters: Int = 500,
    val maxMediaAttachments: Int = 4,
    val imageMimeTypes: List<String> = listOf("image/jpeg", "image/png", "image/gif", "image/webp"),
    val maxImageBytes: Long = 16L * 1024 * 1024,
    val videoAllowed: Boolean = true,
    val maxVideoBytes: Long = 99L * 1024 * 1024,
    val altTextMaxLength: Int = 1500,
    val contentWarning: Boolean = true,
    /** Whether a post may have no picture at all; Pixelfed, for one, refuses that. */
    val textOnly: Boolean = true,
    val pollMaxOptions: Int = 4,
    val pollMaxCharactersPerOption: Int = 50,
    /** Mastodon 4.6 (API version 10) takes a poll and pictures in one post. */
    val pollWithMedia: Boolean = false,
    /** When these were read, so the app can say how fresh they are. */
    val readAt: Long = 0,
)

/** What a new post on a channel starts with. */
@Serializable
data class ChannelDefaults(
    /** A [app.fediferry.data.model.Visibility] name. */
    val visibility: String? = null,
    val templateId: String? = null,
)

@Serializable
data class ChannelDto(
    val id: String,
    val type: String,
    val name: String,
    val instance: String,
    val acct: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val isDefault: Boolean = false,
    val capabilities: ChannelCapabilities = ChannelCapabilities(),
    val defaults: ChannelDefaults = ChannelDefaults(),
)

@Serializable
data class ChannelPatch(val name: String? = null, val isDefault: Boolean? = null, val defaults: ChannelDefaults? = null)

/** Step one of connecting an account: the server registers itself and returns where to sign in. */
@Serializable
data class AuthorizeRequest(val instance: String, val redirectUri: String)

@Serializable
data class AuthorizeResponse(val url: String, val state: String)

/** Step two: the code the instance handed back to the app, sent on to the server. */
@Serializable
data class CompleteRequest(val code: String, val state: String)

/** An account this phone signed in to before, moved over with its token, no new sign-in. */
@Serializable
data class ImportAccountRequest(val instance: String, val accessToken: String, val channelId: String? = null)
