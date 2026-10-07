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
package app.fediferry.server

import app.fediferry.server.db.Media_asset
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.imageio.ImageIO

/**
 * Media files on disk, under `<data>/media/<project>/<ab>/<sha256>`. A file is
 * named by its content, so the same picture is stored once per project and
 * never changes once written. Thumbnails are made on demand and cached.
 */
class MediaStore(
    private val storage: Storage,
    private val dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = storage.db

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun file(projectId: String, sha: String): File = File(dataDir, "media/$projectId/${sha.take(2)}/$sha")

    /**
     * Stores [bytes] for [projectId], or returns the asset that already holds
     * the same content. Pictures get their size read; nothing is re-encoded.
     */
    fun store(projectId: String, bytes: ByteArray, mime: String, parentId: String? = null, recipe: String? = null): Media_asset {
        require(bytes.isNotEmpty()) { "empty upload" }
        require(bytes.size <= MAX_BYTES) { "too large" }
        val sha = sha256(bytes)
        db.mediaAssetQueries.bySha(projectId, sha).executeAsOneOrNull()?.let { return it }

        val target = file(projectId, sha)
        if (!target.exists()) {
            target.parentFile.mkdirs()
            // Written beside and moved into place, so a half-written file is never served.
            val partial = File(target.parentFile, "$sha.partial")
            partial.writeBytes(bytes)
            check(partial.renameTo(target)) { "could not store media" }
        }
        val size = if (mime.startsWith("image/")) dimensions(bytes) else null
        val id = UUID.randomUUID().toString()
        db.mediaAssetQueries.insert(
            projectId, id, sha, mime, size?.first?.toLong(), size?.second?.toLong(), bytes.size.toLong(), parentId, recipe, clock(),
        )
        return db.mediaAssetQueries.byId(projectId, id).executeAsOne()
    }

    fun byId(projectId: String, id: String): Media_asset? = db.mediaAssetQueries.byId(projectId, id).executeAsOneOrNull()

    fun bySha(projectId: String, sha: String): Media_asset? = db.mediaAssetQueries.bySha(projectId, sha).executeAsOneOrNull()

    /**
     * A JPEG no wider than [width], made once and kept. Null for media that is
     * not a picture ImageIO can read, such as a video; the client falls back.
     */
    fun thumbnail(projectId: String, asset: Media_asset, width: Int): File? {
        if (!asset.mime.startsWith("image/")) return null
        val w = width.coerceIn(64, 1600)
        val cached = File(dataDir, "thumbs/$projectId/${asset.sha256}_$w.jpg")
        if (cached.exists()) return cached
        val source = ImageIO.read(file(projectId, asset.sha256)) ?: return null
        val scale = minOf(1.0, w.toDouble() / source.width)
        val tw = maxOf(1, (source.width * scale).toInt())
        val th = maxOf(1, (source.height * scale).toInt())
        val thumb = BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB)
        thumb.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            color = java.awt.Color.WHITE
            fillRect(0, 0, tw, th)
            drawImage(source, 0, 0, tw, th, null)
            dispose()
        }
        cached.parentFile.mkdirs()
        val partial = File(cached.parentFile, cached.name + ".partial")
        ImageIO.write(thumb, "jpg", partial)
        partial.renameTo(cached)
        return cached
    }

    /** Width and height from the picture's header, without decoding the whole of it. */
    private fun dimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    companion object {
        /** Larger than any instance accepts; anything bigger is refused at once. */
        const val MAX_BYTES = 100 * 1024 * 1024
    }
}
