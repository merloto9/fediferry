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

import app.fediferry.api.MediaAssetDto
import app.fediferry.server.db.Media_asset
import org.slf4j.LoggerFactory
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
 *
 * Nothing here is kept for its own sake: a file stays while a post uses it,
 * and [sweep] removes it, with its thumbnails, once none does.
 */
class MediaStore(
    private val storage: Storage,
    private val dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val db get() = storage.db
    private val log = LoggerFactory.getLogger("media")

    /** Storing and sweeping take turns, so a file is never swept while it is being reused. */
    private val lock = Any()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun file(projectId: String, sha: String): File = File(dataDir, "media/$projectId/${sha.take(2)}/$sha")

    /**
     * Stores [bytes] for [projectId], or returns the asset that already holds
     * the same content. Pictures get their size read; nothing is re-encoded.
     */
    fun store(projectId: String, bytes: ByteArray, mime: String, parentId: String? = null, recipe: String? = null): Media_asset = synchronized(lock) {
        require(bytes.isNotEmpty()) { "empty upload" }
        require(bytes.size <= MAX_BYTES) { "too large" }
        val sha = sha256(bytes)
        db.mediaAssetQueries.bySha(projectId, sha).executeAsOneOrNull()?.let {
            db.mediaAssetQueries.touch(clock(), projectId, it.id)
            return db.mediaAssetQueries.byId(projectId, it.id).executeAsOne()
        }

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

    /**
     * Deletes the files of [projectId] no post uses any more, and their
     * thumbnails. What was stored within [graceMs] is spared: it is on its way
     * onto a draft, or an edit not saved yet. Returns how many went.
     */
    fun sweep(projectId: String, graceMs: Long = SWEEP_GRACE_MS): Int = synchronized(lock) {
        remove(projectId, db.mediaAssetQueries.unused(projectId, clock() - graceMs).executeAsList())
    }

    /**
     * Deletes [assetIds] — a deleted post's files — and the originals they were
     * edited from, as far as no other post uses them. A file stored again
     * after [since] is on its way onto another draft and stays.
     */
    fun release(projectId: String, assetIds: Collection<String>, since: Long): Int = synchronized(lock) {
        val candidates = HashSet<String>()
        for (id in assetIds) {
            var current: String? = id
            while (current != null && candidates.add(current)) current = byId(projectId, current)?.parent_asset_id
        }
        remove(projectId, db.mediaAssetQueries.unused(projectId, since + 1).executeAsList().filter { it.id in candidates })
    }

    private fun remove(projectId: String, assets: List<Media_asset>): Int {
        for (asset in assets) {
            db.mediaAssetQueries.delete(projectId, asset.id)
            file(projectId, asset.sha256).delete()
            File(dataDir, "thumbs/$projectId").listFiles { f -> f.name.startsWith(asset.sha256 + "_") }?.forEach { it.delete() }
        }
        if (assets.isNotEmpty()) log.info("Removed {} unused file(s)", assets.size)
        return assets.size
    }

    /** Everything a deleted project had on disk. */
    fun deleteProject(projectId: String) {
        File(dataDir, "media/$projectId").deleteRecursively()
        File(dataDir, "thumbs/$projectId").deleteRecursively()
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

        /** How long a file no post uses yet is left alone. */
        const val SWEEP_GRACE_MS = 60 * 60 * 1000L

        fun assetDto(row: Media_asset) = MediaAssetDto(
            row.id, row.sha256, row.mime, row.width?.toInt(), row.height?.toInt(), row.bytes, row.parent_asset_id,
        )
    }
}
