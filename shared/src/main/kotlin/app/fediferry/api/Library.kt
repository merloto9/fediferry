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

/** A stored file. [id] is stable; [sha256] identifies its content. */
@Serializable
data class MediaAssetDto(
    val id: String,
    val sha256: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val bytes: Long,
    /** The asset this one was made from by an edit, if any. */
    val parentId: String? = null,
)

object LibraryKinds {
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val LINK = "LINK"
    const val TEXT = "TEXT"
}

/** One thing in the library. */
@Serializable
data class LibraryItemDto(
    val id: String,
    val kind: String,
    val asset: MediaAssetDto? = null,
    val title: String? = null,
    val text: String? = null,
    val sourceUrl: String? = null,
    /** A [app.fediferry.data.model.ContentSource] name, when a source module recognised the link. */
    val origin: String? = null,
    val sourceFields: Map<String, String> = emptyMap(),
    /** The link may still name whoever shared it; it could not be cleaned. */
    val linkMayIdentify: Boolean = false,
    val folderId: String? = null,
    val tags: List<String> = emptyList(),
    val deviceId: String? = null,
    val capturedAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** `PATCH /library/items/{id}`: only the fields given change. Tags are replaced as a whole. */
@Serializable
data class LibraryItemPatch(
    val title: String? = null,
    val text: String? = null,
    /** Moves the item; `""` takes it out of every folder. */
    val folderId: String? = null,
    val tags: List<String>? = null,
)

@Serializable
data class LibraryPage(val items: List<LibraryItemDto>, val nextBefore: Long? = null)

@Serializable
data class FolderDto(val id: String, val name: String, val parentId: String? = null, val sortOrder: Int = 0)

@Serializable
data class FolderInput(val name: String, val parentId: String? = null, val sortOrder: Int? = null)

@Serializable
data class TagDto(val id: String, val name: String, val uses: Int = 0)

/** What a share was used for: kept, opened to edit, or posted straight away. */
object IngestModes {
    const val SAVE = "SAVE"
    const val COMPOSE = "COMPOSE"
    const val POST_NOW = "POST_NOW"
}

/**
 * The answer to `PUT /ingest/{clientShareId}`. [paired] says the share filled
 * in the missing half of an earlier one (a link and its screenshot) instead of
 * starting a new item. [replayed] says this share had arrived before.
 */
@Serializable
data class IngestResult(
    val item: LibraryItemDto,
    val paired: Boolean = false,
    val replayed: Boolean = false,
    /** The picture behind a shared link was fetched by the server. */
    val resolved: Boolean = false,
    /** The same picture was already in the library; that item is returned. */
    val duplicate: Boolean = false,
)
