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
package app.fediferry.library

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import app.fediferry.api.IngestModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A share waiting to reach the server. Its [id] is the share id the server keys on. */
@Serializable
data class PendingShare(
    val id: String,
    val mode: String = IngestModes.SAVE,
    val link: String? = null,
    val text: String? = null,
    val mime: String? = null,
    val capturedAt: Long,
    val attempts: Int = 0,
    /** Why the last try failed, as an error code; shown in the library. */
    val lastError: String? = null,
    /** A library folder to put the item in once it is up, by name. */
    val folder: String? = null,
) {
    val hasFile: Boolean get() = mime != null
}

/**
 * Shares kept on the phone until the server has them: one JSON file and, for
 * a picture, the picture beside it, in app-private storage. Taking a share in
 * never needs the network; [UploadWorker] sends them on when it can.
 */
class PendingShares(context: Context) {

    private val dir = File(context.filesDir, "pending-shares").apply { mkdirs() }
    private val resolver = context.contentResolver
    private val json = Json { ignoreUnknownKeys = true }

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<PendingShare>> = _all

    /**
     * Keeps a share. Every picture becomes a share of its own; the first one
     * carries the link and text, the way they arrived together.
     */
    suspend fun add(mode: String, imageUris: List<Uri>, link: String?, text: String?): List<PendingShare> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val added = if (imageUris.isEmpty()) {
            listOf(save(PendingShare(UUID.randomUUID().toString(), mode, link, text, null, now), null))
        } else {
            imageUris.mapIndexed { i, uri ->
                val mime = resolver.getType(uri) ?: guessMime(uri)
                val share = PendingShare(
                    UUID.randomUUID().toString(), mode,
                    link.takeIf { i == 0 }, text.takeIf { i == 0 }, mime, now + i,
                )
                save(share, uri)
            }
        }
        refresh()
        added
    }

    /**
     * Keeps a file that is on the phone already — a post being copied into the
     * library — under a fixed [id], so copying twice never uploads twice.
     */
    suspend fun addFile(id: String, link: String?, file: File?, mime: String?, capturedAt: Long, folder: String?) = withContext(Dispatchers.IO) {
        if (File(dir, "$id.json").exists()) return@withContext
        val share = PendingShare(id, IngestModes.SAVE, link, null, mime?.takeIf { file != null }, capturedAt, folder = folder)
        if (file != null) file.copyTo(file(share), overwrite = true)
        File(dir, "${share.id}.json").writeText(json.encodeToString(share))
        refresh()
    }

    fun has(id: String): Boolean = File(dir, "$id.json").exists()

    fun file(share: PendingShare): File = File(dir, "${share.id}.bin")

    fun update(share: PendingShare) {
        File(dir, "${share.id}.json").writeText(json.encodeToString(share))
        refresh()
    }

    fun remove(share: PendingShare) {
        File(dir, "${share.id}.json").delete()
        file(share).delete()
        refresh()
    }

    private fun save(share: PendingShare, uri: Uri?): PendingShare {
        if (uri != null) {
            val target = file(share)
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("The shared picture could not be read")
        }
        File(dir, "${share.id}.json").writeText(json.encodeToString(share))
        return share
    }

    private fun refresh() {
        _all.value = read()
    }

    private fun read(): List<PendingShare> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString<PendingShare>(it.readText()) }.getOrNull() }
        .sortedBy { it.capturedAt }

    private fun guessMime(uri: Uri): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString())) ?: "image/jpeg"
}
