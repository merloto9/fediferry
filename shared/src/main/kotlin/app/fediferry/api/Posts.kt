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

/**
 * Where a post is in the workflow. Only a DRAFT can be edited; READY and
 * later are frozen, and only a READY post can go back to DRAFT.
 */
object PostStages {
    const val DRAFT = "DRAFT"
    const val READY = "READY"
    const val SCHEDULED = "SCHEDULED"
    const val PUBLISHING = "PUBLISHING"
    const val PUBLISHED = "PUBLISHED"
    const val FAILED = "FAILED"
}

@Serializable
data class PostMediaDto(
    val position: Int,
    val asset: MediaAssetDto,
    val libraryItemId: String? = null,
    val altText: String? = null,
    /** Generating alt text was tried and failed; the post would go out without one. */
    val altFailed: Boolean = false,
)

/** Who is editing a post right now, and until when if they go quiet. */
@Serializable
data class LockDto(val deviceId: String, val deviceName: String, val expiresAt: Long)

@Serializable
data class PostDto(
    val id: String,
    val stage: String,
    /** Grows with every change; a change must name the version it was made on. */
    val version: Long,
    val channelId: String? = null,
    val templateId: String? = null,
    val body: String,
    val hashtags: List<String> = emptyList(),
    val addSourceHashtags: Boolean = true,
    val contentWarning: String? = null,
    val visibility: String = "PUBLIC",
    val sourceUrl: String? = null,
    val origin: String? = null,
    val sourceFields: Map<String, String> = emptyMap(),
    val linkMayIdentify: Boolean = false,
    val media: List<PostMediaDto> = emptyList(),
    /** The text as it will be posted, fixed when the post becomes ready. */
    val finalText: String? = null,
    val lock: LockDto? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val readyAt: Long? = null,
    /** Where the post sits in review; only set once it is ready. */
    val reviewFolderId: String? = null,
    val labels: List<String> = emptyList(),
    /** Its way out, once it is planned. */
    val publication: PublicationDto? = null,
)

/** `POST /posts`: a draft from library items, rendered with the template. */
@Serializable
data class CreatePost(val libraryItemIds: List<String>, val channelId: String? = null, val templateId: String? = null)

/**
 * `PATCH /posts/{id}`, with `If-Match: <version>`, by the device holding the
 * lock. Only given fields change; `""` clears a text field. With [rerender],
 * the body and hashtags are written again from [templateId] (or the current
 * template).
 */
@Serializable
data class PostPatch(
    val body: String? = null,
    val hashtags: List<String>? = null,
    val addSourceHashtags: Boolean? = null,
    val contentWarning: String? = null,
    val visibility: String? = null,
    val channelId: String? = null,
    val templateId: String? = null,
    val rerender: Boolean = false,
    val media: List<PostMediaPatch>? = null,
)

/** One picture's change: its alt text, or a replacement asset (an edit of it). */
@Serializable
data class PostMediaPatch(val position: Int, val altText: String? = null, val assetId: String? = null, val remove: Boolean = false)

/** `POST /media/{id}/derive`: a new picture made from this one. */
@Serializable
data class DeriveRequest(val crop: CropRect? = null, val profileId: String? = null)

/** A rectangle in fractions of the picture: 0,0 is top left, 1,1 bottom right. */
@Serializable
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
data class AltSuggestion(val text: String)

/**
 * Something about a post its channel would not take, or that the person
 * posting should know. A [blocking] one keeps the post from becoming ready.
 */
@Serializable
data class Violation(val code: String, val args: Map<String, String> = emptyMap(), val blocking: Boolean = true)

/**
 * `PATCH /posts/{id}/review`: sorting a ready post. Allowed without the edit
 * lock, since the post itself does not change. `folderId = ""` takes it out
 * of its folder.
 */
@Serializable
data class ReviewPatch(val folderId: String? = null, val labels: List<String>? = null)

@Serializable
data class ReviewFolderDto(val id: String, val name: String, val sortOrder: Int = 0)

@Serializable
data class ReviewFolderInput(val name: String, val sortOrder: Int? = null)

@Serializable
data class LabelDto(val name: String, val uses: Int = 0)
