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

import app.fediferry.api.MediaAssetDto
import app.fediferry.api.PostDto
import app.fediferry.api.PostMediaDto
import app.fediferry.api.PostStages
import app.fediferry.api.PublicationDto
import app.fediferry.api.PublicationStates
import java.time.Instant
import java.util.Base64

/**
 * Posts an instance holds for later (`/api/v1/scheduled_statuses`), shown in
 * a project's queue beside its own. Phone and server describe them alike.
 */
object ScheduledPosts {

    /**
     * The id such a post goes by in the queue: the instance's id and the
     * channel it is on. It cannot be mistaken for a post's own id (a UUID).
     */
    object RemoteId {
        private const val PREFIX = "mastodon-"
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun encode(channelId: String, statusId: String): String =
            PREFIX + statusId + "-" + encoder.encodeToString(channelId.toByteArray())

        /** The channel and the instance's id, or null for a post's own id. */
        fun parse(id: String): Pair<String, String>? {
            if (!id.startsWith(PREFIX)) return null
            val rest = id.removePrefix(PREFIX)
            val statusId = rest.substringBefore('-').takeIf { it.isNotEmpty() } ?: return null
            val channel = runCatching { String(decoder.decode(rest.substringAfter('-'))) }.getOrNull() ?: return null
            return channel to statusId
        }
    }

    /** How it shows in the queue: on its way, at its time, and held by the instance. */
    fun dto(channelId: String, status: ScheduledStatus): PostDto {
        val at = runCatching { Instant.parse(status.scheduledAt).toEpochMilli() }.getOrDefault(0L)
        return PostDto(
            id = RemoteId.encode(channelId, status.id),
            stage = PostStages.SCHEDULED,
            version = 1,
            channelId = channelId,
            body = status.params.text,
            contentWarning = status.params.spoilerText?.ifBlank { null },
            visibility = visibilityOf(status.params.visibility) ?: "PUBLIC",
            media = status.media.mapIndexed { i, m ->
                PostMediaDto(i, MediaAssetDto(m.previewUrl ?: m.url.orEmpty(), "", mimeTypeOf(m), bytes = 0), altText = m.description)
            },
            finalText = status.params.text,
            createdAt = at,
            updatedAt = at,
            publication = PublicationDto(state = PublicationStates.QUEUED, channelId = channelId, publishAfter = at, heldByInstance = true),
        )
    }

    /** Mastodon's visibility word as a post's (PUBLIC, UNLISTED …); null for anything unknown. */
    fun visibilityOf(api: String?): String? = api?.takeIf { it in VISIBILITIES }?.uppercase()

    /**
     * The attachment's media type. Mastodon stores animations ("gifv") as
     * MP4; otherwise the file's extension says it.
     */
    fun mimeTypeOf(attachment: MediaAttachment): String {
        val path = attachment.url.orEmpty().substringBefore('?').lowercase()
        return when {
            attachment.type == "gifv" || attachment.type == "video" || path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /** Mastodon takes a time only this far ahead; sooner it refuses. */
    const val MIN_LEAD_MS = 5 * 60_000L

    private val VISIBILITIES = setOf("public", "unlisted", "private", "direct")
}
