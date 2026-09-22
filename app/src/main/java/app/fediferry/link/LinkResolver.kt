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
package app.fediferry.link

import app.fediferry.data.model.ContentSource

/**
 * The media a shared link points at.
 *
 * @param mediaUrl what to download and attach.
 * @param mimeType what the server is expected to return, so the vault can name
 *   the file before it has the bytes.
 * @param fields what the service says about the post, by the names its
 *   [ContentSource] declares. Blank values are left out. They feed
 *   placeholders only through the user's mappings, and callers must treat
 *   every one as best-effort.
 */
data class ResolvedPost(
    val mediaUrl: String,
    val mimeType: String,
    val fields: Map<String, String> = emptyMap(),
)

/** Keeps the fields that say something; an empty one is the same as a missing one. */
internal fun fieldsOf(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (name, value) -> value?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it } }.toMap()

/**
 * Turns a shared permalink into the media behind it, for services that publish
 * one.
 *
 * Some do not — Instagram most of all — which is why the screenshot path exists
 * and stays the fallback. A resolver must never throw and never block a share:
 * a failure means the item keeps its link and the user carries on screenshotting,
 * exactly as before.
 */
interface LinkResolver {
    /** Which source the fields it returns belong to. */
    val source: ContentSource

    /** The service's own name, for telling the user where a picture is coming from. */
    val serviceName: String get() = source.label

    /** Whether this resolver recognises the link at all. Must not do any I/O. */
    fun handles(url: String): Boolean

    suspend fun resolve(url: String): Result<ResolvedPost>
}
