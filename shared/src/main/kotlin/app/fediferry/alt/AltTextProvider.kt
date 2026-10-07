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
package app.fediferry.alt

/**
 * Resolves alt text for an image.
 *
 * Implementations must never throw: a failure is a [Result.failure], and the
 * posting path treats that as "post without a description" rather than as a
 * reason to abort. Nothing in the posting path may name a concrete vendor.
 */
interface AltTextProvider {
    suspend fun describe(image: ByteArray, mimeType: String): Result<String>
}

/** Emits no description at all. */
object NoAltTextProvider : AltTextProvider {
    override suspend fun describe(image: ByteArray, mimeType: String): Result<String> =
        Result.failure(UnsupportedOperationException("alt text disabled"))
}

/** Emits a fixed string configured on the template. */
class StaticAltTextProvider(private val text: String) : AltTextProvider {
    override suspend fun describe(image: ByteArray, mimeType: String): Result<String> =
        if (text.isBlank()) Result.failure(IllegalStateException("no static alt text configured"))
        else Result.success(text)
}
