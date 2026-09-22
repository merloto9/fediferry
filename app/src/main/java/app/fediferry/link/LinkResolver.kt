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

/**
 * The media a shared link points at.
 *
 * @param mediaUrl what to download and attach.
 * @param mimeType what the server is expected to return, so the vault can name
 *   the file before it has the bytes.
 * @param caption the post's own title, if the service exposes one. Feeds the
 *   `{caption}` placeholder, which callers must still treat as best-effort.
 */
data class ResolvedPost(
    val mediaUrl: String,
    val mimeType: String,
    val caption: String? = null,
)

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
    /** The service's own name, for telling the user where a picture is coming from. */
    val serviceName: String

    /** Whether this resolver recognises the link at all. Must not do any I/O. */
    fun handles(url: String): Boolean

    suspend fun resolve(url: String): Result<ResolvedPost>
}
