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

import app.fediferry.data.model.ContentSource
import app.fediferry.data.model.Hashtags
import app.fediferry.template.TemplateSpec
import kotlinx.serialization.Serializable

/**
 * Fediverse software a channel can post to. Each knows its usual limits,
 * used until the instance itself says otherwise; only [supported] ones can be
 * connected. The instance's software is detected when an account is
 * connected (see [app.fediferry.platform.PlatformDetector]).
 */
enum class Platform(
    val label: String,
    /** The names the software gives itself in its nodeinfo. */
    val software: Set<String>,
    val supported: Boolean,
    val defaultLimits: PlatformLimits,
) {
    MASTODON("Mastodon", setOf("mastodon"), supported = true, PlatformLimits()),
    PIXELFED(
        "Pixelfed", setOf("pixelfed"), supported = false,
        PlatformLimits(
            maxCharacters = 500,
            maxMediaAttachments = 4,
            imageMimeTypes = listOf("image/jpeg", "image/png", "image/gif", "image/webp"),
            maxImageBytes = 15L * 1024 * 1024,
            maxVideoBytes = 15L * 1024 * 1024,
            altTextMaxLength = 1000,
            textOnly = false,
        ),
    ),
    ;

    companion object {
        fun ofSoftware(name: String): Platform? = entries.firstOrNull { name.trim().lowercase() in it.software }
        fun fromName(name: String?): Platform? = entries.firstOrNull { it.name == name }
    }
}

/**
 * What a channel's instance takes: read from the instance where its platform
 * says, else the platform's [Platform.defaultLimits]. A post is checked
 * against these before it can be ready, and the editor shows them while
 * writing.
 */
@Serializable
data class PlatformLimits(
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
    /** When these were read from the instance; 0 while they are the platform's defaults. */
    val readAt: Long = 0,
)

/**
 * How a channel's posts start: the text with its placeholders, the hashtags
 * picked, visibility, content warning and alt text. One per channel — what
 * was a template, picked per post, before.
 */
@Serializable
data class PostSettings(
    override val body: String = "{tags}\n\nvia {link}",
    /** Picks from the project's hashtag list, space-separated. */
    val tags: String = "#meme",
    /** A [app.fediferry.data.model.Visibility] name. */
    val visibility: String = "PUBLIC",
    val contentWarning: String? = null,
    /** NONE, STATIC or VISION. */
    val altTextMode: String = "NONE",
    val staticAltText: String? = null,
    override val addSourceHashtags: Boolean = true,
    /** Sources whose data this channel's text ignores: their placeholders come out empty. */
    val excludedSources: Set<ContentSource> = emptySet(),
) : TemplateSpec {
    override val hashtagList: List<String> get() = Hashtags.parse(tags)
    override fun usesSource(source: ContentSource): Boolean = source !in excludedSources
}

/** The fediverse account a channel posts as. Its credential stays with the backend. */
@Serializable
data class ChannelAccount(
    /** A [Platform] name. */
    val platform: String,
    val instance: String,
    val acct: String,
    val displayName: String = "",
    val avatarUrl: String? = null,
)

/**
 * A destination of a project: one fediverse account, its instance's limits,
 * and how the project's posts there start. Every post goes to one channel;
 * schedules belong to one. A channel can be set up before its account is
 * connected, but nothing goes out on it until then.
 */
@Serializable
data class ChannelDto(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val account: ChannelAccount? = null,
    val limits: PlatformLimits = PlatformLimits(),
    val post: PostSettings = PostSettings(),
) {
    /** How the channel is named in lists: its account's handle, or its name before one is connected. */
    val label: String get() = account?.let { "@${it.acct}" } ?: name

    /** Its name with its account's handle, for pickers: "Memes · @memes". */
    val title: String get() = account?.let { "$name · @${it.acct}" } ?: name
}

/** `POST /channels`: a new channel, with no account yet. */
@Serializable
data class ChannelInput(val name: String, val post: PostSettings? = null)

@Serializable
data class ChannelPatch(val name: String? = null, val isDefault: Boolean? = null, val post: PostSettings? = null)

/**
 * Step one of connecting an account: the server detects the instance's
 * platform, registers itself and returns where to sign in. The account goes
 * to [channelId], or to a new channel when there is none.
 */
@Serializable
data class AuthorizeRequest(val instance: String, val redirectUri: String, val channelId: String? = null)

@Serializable
data class AuthorizeResponse(val url: String, val state: String)

/** Step two: the code the instance handed back to the app, sent on to the server. */
@Serializable
data class CompleteRequest(val code: String, val state: String)

/** An account this phone signed in to before, moved over with its token, no new sign-in. */
@Serializable
data class ImportAccountRequest(val instance: String, val accessToken: String, val channelId: String? = null)
