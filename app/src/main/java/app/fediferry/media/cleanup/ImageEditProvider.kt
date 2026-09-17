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
package app.fediferry.media.cleanup

/**
 * What to send a model that erases part of a picture.
 *
 * @param image the whole picture, so the model has the context around the hole.
 * @param mask a PNG the same size as [image], marking what to replace. Which
 *   way round the marking goes differs by vendor; see [MaskPolarity].
 * @param instruction free text, for the services that take a prompt. Services
 *   that do not simply ignore it.
 */
data class EraseRequest(
    val image: ByteArray,
    val mimeType: String,
    val mask: ByteArray,
    val instruction: String,
) {
    // ByteArray identity would make two equal requests unequal.
    override fun equals(other: Any?): Boolean =
        other is EraseRequest && mimeType == other.mimeType &&
            instruction == other.instruction &&
            image.contentEquals(other.image) && mask.contentEquals(other.mask)

    override fun hashCode(): Int =
        ((image.contentHashCode() * 31 + mask.contentHashCode()) * 31 +
            mimeType.hashCode()) * 31 + instruction.hashCode()
}

/**
 * Erases marked regions of an image with a model, for the cases local pixel
 * pushing cannot do well — a badge sitting on detail, or a watermark across
 * the middle of a picture.
 *
 * The same contract as [app.fediferry.alt.AltTextProvider]: never throws, and a
 * failure is a [Result.failure] rather than something that stops a post. The
 * cleanup pipeline falls back to filling the region locally, so a model that is
 * unreachable, slow or unconfigured costs quality, never the post.
 *
 * No vendor is named anywhere downstream of the implementation.
 */
interface ImageEditProvider {
    suspend fun erase(request: EraseRequest): Result<ByteArray>
}

/** No model configured; every region falls back to a local treatment. */
object NoImageEditProvider : ImageEditProvider {
    override suspend fun erase(request: EraseRequest): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("no image model configured"))
}

/** Which way a service expects the region-to-replace to be marked. */
enum class MaskPolarity {
    /** The area to replace is transparent; the rest opaque. OpenAI-style. */
    TRANSPARENT_HOLE,

    /** The area to replace is white; the rest black. Stable-Diffusion-style. */
    WHITE_ON_BLACK,
}

/** How the request is put on the wire. There is no standard here. */
enum class EditWireFormat {
    /** multipart/form-data with image, mask and prompt parts. */
    MULTIPART,

    /** JSON with base64 image and mask. */
    JSON_BASE64,
}
