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
package app.fediferry.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * App-private media storage. Deliberately not MediaStore: shared screenshots are
 * copied into our own sandbox on ingest so nothing downstream depends on a
 * content URI staying grantable, and so uninstalling the app takes its copies
 * with it.
 */
class MediaVault(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "media").apply { mkdirs() }

    data class Stored(val file: File, val sha256: String, val mimeType: String)

    /**
     * Copies [uri] into private storage under its content hash. Re-ingesting the
     * same screenshot therefore reuses the existing file rather than duplicating
     * it; callers dedupe on [Stored.sha256].
     */
    suspend fun ingest(uri: Uri): Result<Stored> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = resolveMimeType(uri)
            val tmp = File.createTempFile("ingest", null, context.cacheDir)
            val digest = MessageDigest.getInstance("SHA-256")

            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        digest.update(buf, 0, read)
                        output.write(buf, 0, read)
                    }
                }
            } ?: error("Cannot open shared media")

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(dir, hash + extensionFor(mime))
            if (target.exists()) tmp.delete() else check(tmp.renameTo(target)) {
                "Cannot move ingested media into place"
            }
            Stored(target, hash, mime)
        }
    }

    suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.isFile }?.readBytes()
    }

    /** Drops a media file once no item references it. */
    suspend fun deleteIfUnreferenced(path: String?, stillReferenced: Boolean) =
        withContext(Dispatchers.IO) {
            if (path != null && !stillReferenced) File(path).delete()
        }

    private fun resolveMimeType(uri: Uri): String =
        context.contentResolver.getType(uri)
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()))
            ?: "image/*"

    private fun extensionFor(mime: String): String = when (mime) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "video/mp4" -> ".mp4"
        else -> ".jpg"
    }
}
